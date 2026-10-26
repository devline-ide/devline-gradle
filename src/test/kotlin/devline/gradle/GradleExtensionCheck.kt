package devline.gradle

import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import org.gradle.tooling.GradleConnector
import java.nio.file.Files
import java.nio.file.Path

/** Optional real tooling acceptance: arguments are an owned QA parent and a cached Gradle installation. */
fun main(args: Array<String>) {
    val buffer = GradleOutputBuffer(4096)
    val stream = GradleOutputStream(buffer)
    val message = "Привет, Gradle 👋\n"
    for (byte in message.toByteArray(Charsets.UTF_8)) stream.write(byte.toInt())
    stream.close()
    check(buffer.page(0).output.joinToString("") { it.text } == message)
    repeat(20) { buffer.append("x".repeat(1000)) }
    val truncated = buffer.page(0)
    check(truncated.truncated && truncated.output.sumOf { it.text.length } <= 4096)
    check(buffer.page(truncated.nextCursor).output.isEmpty())
    val paged = GradleOutputBuffer(64000).apply { append("output\n".repeat(5000)) }
    var outputCursor = 0L
    var collected = 0
    do {
        val page = paged.page(outputCursor)
        check(page.nextCursor > outputCursor)
        outputCursor = page.nextCursor
        collected += page.output.sumOf { it.text.length }
    } while (page.hasMore)
    check(collected == 35000)
    val emojiPage = GradleOutputBuffer().apply { append("x".repeat(2047) + "👋" + "tail") }.page(0)
    check(emojiPage.output.none { it.text.last().isHighSurrogate() })

    val parent = Path.of(args[0]).toAbsolutePath().also(Files::createDirectories)
    registryContentionCheck(parent)
    val fixture = Files.createTempDirectory(parent, "gradle-fixture-")
    Files.writeString(fixture.resolve("settings.gradle"), "rootProject.name = 'Gradle fixture'\ninclude 'app'\n")
    Files.createDirectories(fixture.resolve("app"))
    Files.writeString(fixture.resolve("build.gradle"), """
        tasks.register('greet') {
            group = 'verification'
            description = 'UTF-8 tooling test'
            doLast { println 'Привет, Gradle'; file('result.txt').text = 'complete' }
        }
        tasks.register('waitForCancel') {
            doLast { 60.times { println 'waiting'; Thread.sleep(100) } }
        }
    """.trimIndent())
    Files.writeString(fixture.resolve("app/build.gradle"), "tasks.register('childTask') { group = 'verification' }\n")
    val profile = fixture.resolve("profile")
    var active = true
    var owner: String? = "original"
    val context = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = active
        override fun currentProjectFile() = owner
        override fun projectRoot(projectFile: String): String {
            check(projectFile == owner) { "Stale project owner" }
            return fixture.toString()
        }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject) = error("No host write expected")
    }
    val installation = args.getOrNull(1)?.let(Path::of)
    var connections = 0
    val connect: (GradleRoot) -> org.gradle.tooling.ProjectConnection = { root ->
        connections++
        check(installation != null) { "The cheap overview must never create a tooling connection." }
        GradleConnector.newConnector().forProjectDirectory(root.path.toFile()).useInstallation(installation.toFile())
            .useGradleUserHomeDir(parent.resolve("gradle-home").toFile()).connect()
    }
    fun arguments(vararg pairs: Pair<String, String>) = JsonObject().apply { pairs.forEach { addProperty(it.first, it.second) } }
    GradleRuntime(context, connect).use { runtime ->
        fun call(method: String, params: JsonObject = JsonObject()) = runtime.request(method, params).asJsonObject
        check(call("overview")["available"].asBoolean)
        check(!call("overview")["synced"].asBoolean && connections == 0)
        check(!Files.exists(fixture.resolve(".gradle")))
        val noSync = runCatching { call("run", JsonObject().apply { add("tasks", com.google.gson.JsonArray().apply { add(":greet") }) }) }.exceptionOrNull()
        check(noSync is IdeExtensionException && noSync.code == "GRADLE_SYNC_REQUIRED")
        check(call("connect", arguments("rootPath" to fixture.toString()))["selectionSource"].asString == "linked")
        check(connections == 0)
        if (installation != null) {
            fun awaitOperation(id: String): JsonObject {
                val deadline = System.nanoTime() + 90_000_000_000L
                while (System.nanoTime() < deadline) {
                    val state = call("state", arguments("operationId" to id))
                    val status = state["operation"].asJsonObject["status"].asString
                    if (status !in listOf("running", "cancelling") && state["operation"].asJsonObject.has("finishedAt")) return state
                    Thread.sleep(50)
                }
                error("Gradle fixture timed out")
            }
            val sync = call("sync")["operation"].asJsonObject["id"].asString
            val imported = awaitOperation(sync)
            check(imported["operation"].asJsonObject["status"].asString == "succeeded") { imported.toString() }
            check(imported["projects"].asJsonArray.map { it.asJsonObject["path"].asString }.containsAll(listOf(":", ":app")))
            val tasks = imported["tasks"].asJsonArray.map { it.asJsonObject["path"].asString }
            check(":greet" in tasks && ":app:childTask" in tasks)
            val childTasks = call("state", arguments("taskProject" to ":app"))["tasks"].asJsonArray
            check(childTasks.size() > 0 && childTasks.all { it.asJsonObject["projectPath"].asString == ":app" })
            fun runTask(path: String) = call("run", JsonObject().apply { add("tasks", com.google.gson.JsonArray().apply { add(path) }) })["operation"].asJsonObject["id"].asString
            check(runCatching { runTask(":unknown") }.exceptionOrNull().let { it is IdeExtensionException && it.code == "GRADLE_INVALID_TASK" })
            val task = runTask(":greet")
            check(awaitOperation(task)["operation"].asJsonObject["status"].asString == "succeeded")
            check(Files.readString(fixture.resolve("result.txt")) == "complete")
            val waiting = runTask(":waitForCancel")
            val busy = runCatching { call("sync") }.exceptionOrNull()
            check(busy is IdeExtensionException && busy.code == "GRADLE_BUSY")
            Thread.sleep(300)
            call("stop", arguments("operationId" to waiting))
            check(awaitOperation(waiting)["operation"].asJsonObject["status"].asString == "cancelled")
            val old = runTask(":waitForCancel")
            owner = "new-owner"
            runtime.projectClosed()
            check(!call("overview")["synced"].asBoolean)
            val stale = runCatching { call("state", arguments("operationId" to old)) }.exceptionOrNull()
            check(stale is IdeExtensionException && stale.code == "GRADLE_OPERATION_NOT_FOUND")
        }
        active = false
        val disabled = runCatching { call("overview") }.exceptionOrNull()
        check(disabled is IdeExtensionException && disabled.code == "IDE_EXTENSION_DISABLED")
    }
    active = true
    GradleRuntime(context, connect).use { restored ->
        check(restored.request("overview", JsonObject()).asJsonObject["selectionSource"].asString == "linked")
        check(restored.request("disconnect", JsonObject()).asJsonObject["selectionSource"].asString == "workspace")
    }
    println("PASS Gradle: UTF-8 streaming, bounded output, cheap detection, explicit linking, persistence, owner invalidation, disabled runtime, contended registry polling/cancellation" +
        if (installation != null) ", real multiproject tooling discovery, task execution and cancellation." else ". Real tooling fixture was not requested.")
}

/** Deterministically models registry -> runtime dispatch while a worker waits for the registry. */
private fun registryContentionCheck(parent: Path) {
    val root = Files.createTempDirectory(parent, "gradle-lock-fixture-")
    Files.writeString(root.resolve("build.gradle"), "// Detection only, no Gradle process is needed.\n")
    val registry = Any()
    val callbackEntered = java.util.concurrent.CountDownLatch(1)
    val uiCompleted = java.util.concurrent.CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val context = object : IdeExtensionContext {
        override val profileDirectory = root.resolve("profile")
        override fun enabled(): Boolean {
            if (Thread.currentThread().name == "devline-gradle") callbackEntered.countDown()
            return synchronized(registry) { true }
        }
        override fun currentProjectFile() = synchronized(registry) { "lock-owner" }
        override fun projectRoot(projectFile: String) = synchronized(registry) {
            check(projectFile == "lock-owner")
            root.toString()
        }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject) = error("No project writes expected")
    }
    val runtime = GradleRuntime(context) { error("The cancelled operation must not connect to Gradle.") }
    val ui = Thread({
        try {
            synchronized(registry) {
                runtime.request("sync", JsonObject())
                check(callbackEntered.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Worker did not reach the host callback." }
                check(runtime.request("state", JsonObject()).asJsonObject["operation"].asJsonObject["status"].asString == "running")
                check(runtime.request("stop", JsonObject()).asJsonObject["operation"].asJsonObject["status"].asString == "cancelling")
            }
        } catch (cause: Throwable) { failure.set(cause) }
        finally { uiCompleted.countDown() }
    }, "gradle-registry-fixture").apply { isDaemon = true }
    ui.start()
    check(uiCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Registry/runtime lock inversion blocked polling or cancellation." }
    runtime.close()
    failure.get()?.let { throw AssertionError("Contended registry regression failed.", it) }
}

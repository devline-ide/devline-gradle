package devline.gradle

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionFiles
import devline.extensions.IdeExtensionRuntime
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.GradleProject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** RPC only schedules work. Tooling configuration and execution belong to the worker. */
internal class GradleRuntime(private val context: IdeExtensionContext,
    private val connect: (GradleRoot) -> ProjectConnection = ::connectGradle) : IdeExtensionRuntime {
    private val gson = Gson()
    private val lock = Any()
    private val settingsFile = context.profileDirectory.resolve("devline.gradle/settings.json")
    private var linkedRoot: Path? = readLinkedRoot()
    private var closed = false
    private var generation = 0L
    private var selected: Selection? = null
    private var model: GradleModel? = null
    private var operation: Operation? = null
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { action ->
        Thread(action, "devline-gradle").apply { isDaemon = true; contextClassLoader = GradleRuntime::class.java.classLoader }
    })

    private data class Selection(val owner: String?, val root: GradleRoot, val generation: Long)
    private class Operation(val selection: Selection, val kind: String) {
        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val cancellation: CancellationTokenSource = GradleConnector.newCancellationTokenSource()
        val output = GradleOutputBuffer()
        var status = "running"
        var finishedAt: Long? = null
        var error: String? = null
        var errorCode: String? = null
        fun view() = linkedMapOf<String, Any?>("id" to id, "kind" to kind, "status" to status,
            "startedAt" to startedAt, "finishedAt" to finishedAt, "error" to error, "errorCode" to errorCode)
    }

    override fun request(method: String, params: JsonObject): JsonElement = synchronized(lock) {
        ensureEnabled()
        val owner = string(params, "expectedProjectFile") ?: context.currentProjectFile()
        owner?.let { context.projectRoot(it) }
        when (method.substringAfterLast('.')) {
            "connect" -> {
                val value = string(params, "rootPath") ?: fail("GRADLE_INVALID_ROOT", "Choose a Gradle build directory.")
                val root = try {
                    val path = Path.of(value)
                    if (!path.isAbsolute) fail("GRADLE_INVALID_ROOT", "The Gradle build directory must be absolute.")
                    path.normalize()
                } catch (error: IdeExtensionException) { throw error }
                  catch (failure: Exception) { fail("GRADLE_INVALID_ROOT", "Cannot open the Gradle build directory: ${failure.message}") }
                if (!Files.isDirectory(root) || !GradleRoot.detect(root, "linked").available)
                    fail("GRADLE_INVALID_ROOT", "Choose a directory containing a Gradle build or wrapper.")
                saveLinkedRoot(root)
                invalidate()
                linkedRoot = root
                overview(selection(owner), params)
            }
            "disconnect" -> {
                saveLinkedRoot(null)
                invalidate()
                linkedRoot = null
                overview(selection(owner), params)
            }
            "overview" -> overview(selection(owner), params)
            "sync" -> {
                val selection = requireBuild(selection(owner))
                start(selection, "sync", emptyList())
                overview(selection, params)
            }
            "run" -> {
                val selection = requireBuild(selection(owner))
                val discovered = model ?: fail("GRADLE_SYNC_REQUIRED", "Sync this Gradle build before running tasks.")
                val values = params["tasks"]?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: fail("GRADLE_INVALID_TASK", "Select one or more discovered Gradle tasks.")
                if (values.size() !in 1..32) fail("GRADLE_INVALID_TASK", "Select between 1 and 32 Gradle tasks.")
                val tasks = values.map {
                    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) fail("GRADLE_INVALID_TASK", "Task paths must be strings.")
                    it.asString
                }.distinct()
                val paths = discovered.tasks.mapTo(hashSetOf()) { it.path }
                if (tasks.any { it !in paths }) fail("GRADLE_INVALID_TASK", "Only tasks discovered by the current sync can run.")
                start(selection, "run", tasks)
                overview(selection, params)
            }
            "stop" -> {
                val selection = selection(owner)
                verifyOperationId(params)
                operation?.takeIf { it.status == "running" }?.let { cancel(it) }
                state(selection, params)
            }
            "state" -> {
                val selection = selection(owner)
                verifyOperationId(params)
                state(selection, params)
            }
            else -> fail("GRADLE_UNKNOWN_METHOD", "Unknown Gradle action.")
        }
    }

    private fun selection(owner: String?): Selection? {
        val path = linkedRoot ?: owner?.let { Path.of(context.projectRoot(it)).toAbsolutePath().normalize() }
        if (path == null) {
            if (selected != null) invalidate()
            return null
        }
        val root = GradleRoot.detect(path, if (linkedRoot != null) "linked" else "workspace")
        val previous = selected
        if (previous == null || previous.owner != owner || previous.root != root) {
            invalidate()
            selected = Selection(owner, root, generation)
        }
        return selected
    }

    private fun overview(selection: Selection?, params: JsonObject): JsonObject = JsonObject().apply {
        addProperty("available", selection?.root?.available == true)
        addProperty("rootPath", selection?.root?.path?.toString())
        addProperty("rootName", selection?.root?.path?.fileName?.toString())
        addProperty("selectionSource", selection?.root?.source ?: "workspace")
        add("buildFiles", gson.toJsonTree(selection?.root?.buildFiles ?: emptyList<String>()))
        addProperty("wrapper", selection?.root?.wrapper == true)
        addProperty("gradleSource", if (selection?.root?.wrapper == true) "wrapper" else "fallback")
        addProperty("fallbackVersion", FALLBACK_GRADLE_VERSION)
        addProperty("synced", model != null)
        add("operation", operation?.let { gson.toJsonTree(it.view()) } ?: JsonNull.INSTANCE)
        modelPage(this, params)
    }

    private fun state(selection: Selection?, params: JsonObject): JsonObject = overview(selection, params).apply {
        val page = operation?.output?.page(long(params, "cursor")) ?: GradleOutputPage(emptyList(), 0, false)
        add("output", gson.toJsonTree(page.output))
        addProperty("nextCursor", page.nextCursor)
        addProperty("truncated", page.truncated)
        addProperty("hasMoreOutput", page.hasMore)
    }

    private fun modelPage(result: JsonObject, params: JsonObject) {
        val query = string(params, "taskQuery").orEmpty().take(200)
        val project = string(params, "taskProject")
        val tasks = model?.tasks.orEmpty().filter {
            (project == null || it.projectPath == project) &&
                (query.isEmpty() || it.path.contains(query, true) || it.group.orEmpty().contains(query, true))
        }
        fun <T> page(name: String, values: List<T>, offsetName: String, nextName: String, totalName: String) {
            val offset = long(params, offsetName).coerceAtMost(values.size.toLong()).toInt()
            val array = com.google.gson.JsonArray()
            var size = 0
            var end = offset
            for (value in values.drop(offset).take(100)) {
                val item = gson.toJsonTree(value)
                val length = item.toString().length
                if (size + length > 24000 && array.size() > 0) break
                array.add(item)
                size += length
                end++
            }
            result.add(name, array)
            result.addProperty(offsetName, offset)
            result.addProperty(nextName, end)
            result.addProperty(totalName, values.size)
        }
        page("projects", model?.projects.orEmpty(), "projectOffset", "nextProjectOffset", "projectsTotal")
        page("tasks", tasks, "taskOffset", "nextTaskOffset", "tasksTotal")
    }

    private fun start(selection: Selection, kind: String, tasks: List<String>) {
        if (operation?.status in listOf("running", "cancelling")) fail("GRADLE_BUSY", "A Gradle operation is already running. Stop it or wait for completion.")
        val next = Operation(selection, kind)
        val previous = operation
        operation = next
        try { worker.execute { execute(next, tasks) } }
        catch (_: RejectedExecutionException) {
            operation = previous
            fail("GRADLE_BUSY", "Gradle is still cancelling an earlier operation. Wait for it to finish.")
        }
    }

    private fun execute(job: Operation, tasks: List<String>) {
        var connection: ProjectConnection? = null
        val stdout = GradleOutputStream(job.output)
        val stderr = GradleOutputStream(job.output)
        try {
            checkCurrent(job)
            val root = job.selection.root
            job.output.append("${if (job.kind == "sync") "Sync" else "Run ${tasks.joinToString(" ")}"}: ${root.path}\n")
            job.output.append(if (root.wrapper) "Using the project Gradle wrapper.\n" else "Using Gradle $FALLBACK_GRADLE_VERSION. Its distribution may be downloaded into the Gradle cache.\n")
            connection = connect(root)
            checkCurrent(job)
            if (job.kind == "sync") {
                val imported = connection.model(GradleProject::class.java).withCancellationToken(job.cancellation.token())
                    .setStandardOutput(stdout).setStandardError(stderr).setColorOutput(false).get()
                val discovered = GradleModel.read(imported)
                checkCurrent(job)
                synchronized(lock) {
                    if (current(job)) model = discovered
                    else throw InterruptedException("Gradle project owner changed.")
                }
                job.output.append("Discovered ${discovered.projects.size} projects and ${discovered.tasks.size} tasks.\n")
            } else {
                connection.newBuild().forTasks(*tasks.toTypedArray()).withCancellationToken(job.cancellation.token())
                    .setStandardOutput(stdout).setStandardError(stderr).setColorOutput(false).run()
                checkCurrent(job)
            }
            synchronized(lock) { job.status = if (current(job)) "succeeded" else "cancelled" }
        } catch (failure: Throwable) {
            val cancelled = failure is BuildCancelledException || failure is InterruptedException || job.cancellation.token().isCancellationRequested
            synchronized(lock) {
                job.status = if (cancelled) "cancelled" else "failed"
                if (!cancelled) {
                    job.errorCode = "GRADLE_OPERATION_FAILED"
                    job.error = (failure.message ?: failure.javaClass.simpleName).take(3000)
                }
            }
            job.output.append(if (cancelled) "Gradle operation cancelled.\n" else failure.stackTraceToString().take(12000) + "\n")
        } finally {
            try { connection?.close() } catch (failure: Exception) { job.output.append("Gradle connection close: ${failure.message}\n") }
            stdout.close()
            stderr.close()
            synchronized(lock) { job.finishedAt = System.currentTimeMillis() }
        }
    }

    private fun checkCurrent(job: Operation) {
        // Host callbacks can acquire the registry lock. Never call them while holding this runtime's lock:
        // the registry dispatches UI requests in the opposite order (registry -> runtime).
        if (job.cancellation.token().isCancellationRequested || !context.enabled()) throw InterruptedException("Gradle operation cancelled.")
        // Preserve the original host identity for deferred work, never ask for a newer owner.
        if (context.currentProjectFile() != job.selection.owner) throw InterruptedException("Gradle project owner changed.")
        job.selection.owner?.let { context.projectRoot(it) }
        if (job.cancellation.token().isCancellationRequested || !synchronized(lock) { current(job) }) throw InterruptedException("Gradle operation cancelled.")
    }

    /** Local state only; callers hold lock. Host callbacks belong outside this monitor. */
    private fun current(job: Operation) = !closed && selected == job.selection && generation == job.selection.generation
    private fun cancel(job: Operation) { job.status = "cancelling"; job.cancellation.cancel() }
    private fun invalidate() {
        operation?.takeIf { it.status in listOf("running", "cancelling") }?.let { cancel(it) }
        generation++
        selected = null
        model = null
        operation = null
    }

    override fun projectClosed() = synchronized(lock) { invalidate() }
    override fun close() = synchronized(lock) {
        closed = true
        invalidate()
        worker.shutdown()
    }

    private fun readLinkedRoot(): Path? {
        if (!Files.isRegularFile(settingsFile)) return null
        return try {
            require(Files.size(settingsFile) <= 16384) { "Gradle settings file is too large." }
            Files.newBufferedReader(settingsFile, Charsets.UTF_8).use {
                gson.fromJson(it, JsonObject::class.java)?.get("rootPath")?.takeUnless(JsonElement::isJsonNull)?.asString?.let(Path::of)
            }?.takeIf { it.isAbsolute }
        } catch (failure: Exception) { throw IdeExtensionException("GRADLE_SETTINGS_ERROR", "Cannot read Gradle settings: ${failure.message}") }
    }

    private fun saveLinkedRoot(path: Path?) {
        try {
            IdeExtensionFiles.writeAtomic(settingsFile, gson.toJson(mapOf("schemaVersion" to 1, "rootPath" to path?.toString())))
        } catch (failure: Exception) { fail("GRADLE_SETTINGS_ERROR", "Cannot save the selected Gradle build: ${failure.message}") }
    }

    private fun verifyOperationId(params: JsonObject) {
        val id = string(params, "operationId") ?: return
        if (operation?.id != id) fail("GRADLE_OPERATION_NOT_FOUND", "This Gradle operation is no longer available.")
    }
    private fun requireBuild(selection: Selection?): Selection = selection?.takeIf { it.root.available }
        ?: fail("GRADLE_NOT_FOUND", "No Gradle build was found. Connect a Gradle build directory.")
    private fun ensureEnabled() { if (closed || !context.enabled()) fail("IDE_EXTENSION_DISABLED", "The Gradle extension is disabled.") }
    private fun string(params: JsonObject, name: String): String? = params[name]?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) fail("GRADLE_INVALID_ARGUMENT", "$name must be a string.")
        it.asString.takeIf(String::isNotBlank)
    }
    private fun long(params: JsonObject, name: String): Long = try {
        params[name]?.takeUnless { it.isJsonNull }?.asLong?.coerceAtLeast(0) ?: 0
    } catch (_: Exception) { fail("GRADLE_INVALID_ARGUMENT", "$name must be a nonnegative integer.") }
    private fun fail(code: String, message: String): Nothing = throw IdeExtensionException(code, message)
}

private fun connectGradle(root: GradleRoot): ProjectConnection {
    val connector = GradleConnector.newConnector().forProjectDirectory(root.path.toFile())
    // The wrapper is authoritative. A build without a wrapper uses this explicitly pinned version.
    if (root.wrapper) connector.useBuildDistribution() else connector.useGradleVersion(FALLBACK_GRADLE_VERSION)
    return connector.connect()
}

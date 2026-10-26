package devline.gradle

import org.gradle.tooling.model.GradleProject
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

internal const val FALLBACK_GRADLE_VERSION = "9.7.1"

internal data class GradleRoot(val path: Path, val source: String, val buildFiles: List<String>, val wrapper: Boolean) {
    val available: Boolean get() = buildFiles.isNotEmpty() || wrapper
    companion object {
        fun detect(path: Path, source: String): GradleRoot {
            val files = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
                .filter { Files.isRegularFile(path.resolve(it)) }
            return GradleRoot(path, source, files, Files.isRegularFile(path.resolve("gradle/wrapper/gradle-wrapper.properties")))
        }
    }
}

internal data class GradleProjectView(val path: String, val name: String, val parentPath: String?, val children: List<String>, val childrenTotal: Int)
internal data class GradleTaskView(val path: String, val name: String, val projectPath: String, val group: String?, val description: String?)
internal data class GradleModel(val projects: List<GradleProjectView>, val tasks: List<GradleTaskView>) {
    companion object {
        fun read(root: GradleProject): GradleModel {
            val projects = arrayListOf<GradleProjectView>()
            val tasks = linkedMapOf<String, GradleTaskView>()
            val pending = ArrayDeque<GradleProject>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val project = pending.removeFirst()
                require(projects.size < 2000) { "Gradle build exceeds the supported 2000 projects." }
                require(project.path.length <= 4096) { "Gradle project path exceeds 4096 characters." }
                val children = project.children.sortedBy { it.path }
                var childCharacters = 0
                val childPaths = children.map { it.path }.takeWhile { childCharacters += it.length; childCharacters <= 8000 }
                projects += GradleProjectView(project.path, project.name.take(300), project.parent?.path, childPaths, children.size)
                for (task in project.tasks) {
                    require(tasks.size < 50000) { "Gradle build exceeds the supported 50000 tasks." }
                    require(task.path.length <= 4096) { "Gradle task path exceeds 4096 characters." }
                    tasks[task.path] = GradleTaskView(task.path, task.name.take(300), project.path, task.group?.take(300), task.description?.take(600))
                }
                pending.addAll(children)
            }
            return GradleModel(projects, tasks.values.sortedBy { it.path })
        }
    }
}

internal data class GradleOutput(val cursor: Long, val text: String)
internal data class GradleOutputPage(val output: List<GradleOutput>, val nextCursor: Long, val truncated: Boolean, val hasMore: Boolean = false)

/** Monotonic cursors survive eviction; callers can detect a missed section. */
internal class GradleOutputBuffer(private val limit: Int = 256 * 1024) {
    private val chunks = ArrayDeque<GradleOutput>()
    private var length = 0
    private var cursor = 0L

    @Synchronized fun append(value: String) {
        var offset = 0
        while (offset < value.length) {
            var end = minOf(offset + 2048, value.length)
            if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            val text = value.substring(offset, end)
            chunks.addLast(GradleOutput(++cursor, text))
            length += text.length
            while (length > limit && chunks.size > 1) length -= chunks.removeFirst().text.length
            offset = end
        }
    }

    @Synchronized fun page(after: Long): GradleOutputPage {
        val result = arrayListOf<GradleOutput>()
        var size = 0
        for (chunk in chunks) if (chunk.cursor > after) {
            if (size + chunk.text.length > 16000 && result.isNotEmpty()) break
            result += chunk
            size += chunk.text.length
        }
        val next = result.lastOrNull()?.cursor ?: after.coerceAtMost(cursor)
        return GradleOutputPage(result, next, chunks.firstOrNull()?.let { after < it.cursor - 1 } ?: false, next < cursor)
    }
}

/** Retains incomplete UTF-8 sequences between tooling callbacks. */
internal class GradleOutputStream(private val output: GradleOutputBuffer) : OutputStream() {
    private var pending = ByteArray(0)
    @Synchronized override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length
        while (index < end) {
            val count = minOf(4096, end - index)
            val combined = pending + bytes.copyOfRange(index, index + count)
            val input = java.nio.ByteBuffer.wrap(combined)
            val characters = java.nio.CharBuffer.allocate(combined.size + 1)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            decoder.decode(input, characters, false)
            characters.flip()
            output.append(characters.toString())
            pending = ByteArray(input.remaining()).also { input.get(it) }
            index += count
        }
    }
    @Synchronized override fun close() {
        if (pending.isNotEmpty()) output.append(String(pending, Charsets.UTF_8))
        pending = ByteArray(0)
    }
}

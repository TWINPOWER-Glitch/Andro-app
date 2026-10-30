package com.twinpower.andro

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ProjectInfo(
    val id: String,
    val name: String,
    val rootPath: String,
    val hasGradleWrapper: Boolean
)

data class ToolchainStatus(val ready: Boolean, val message: String)

class ProjectRepository(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
    val toolchainDir = File(context.filesDir, "toolchain")

    fun listProjects(): List<ProjectInfo> = projectsDir.listFiles()
        ?.filter { it.isDirectory && File(it, ".andro-project.json").exists() }
        ?.mapNotNull { readProjectInfo(it) }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    suspend fun importProject(treeUri: Uri): ProjectInfo = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open selected directory")
        val id = UUID.randomUUID().toString()
        val safeName = source.name?.takeIf { it.isNotBlank() } ?: "Android project"
        val destination = File(projectsDir, id).apply { mkdirs() }
        copyTree(source, destination)
        val hasWrapper = File(destination, "gradlew").exists()
        val metadata = JSONObject()
            .put("id", id)
            .put("name", safeName)
            .put("importedAt", System.currentTimeMillis())
        File(destination, ".andro-project.json").writeText(metadata.toString())
        ProjectInfo(id, safeName, destination.absolutePath, hasWrapper)
    }

    suspend fun importToolchain(treeUri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open selected toolchain directory")
        val staging = File(context.filesDir, "toolchain-import").apply {
            deleteRecursively()
            mkdirs()
        }
        copyTree(source, staging)
        val root = if (File(staging, "bin/bash").exists()) staging
        else if (File(staging, "usr/bin/bash").exists()) File(staging, "usr")
        else error("Toolchain must contain bin/bash or usr/bin/bash")
        val finalDir = File(context.filesDir, "toolchain")
        finalDir.deleteRecursively()
        root.copyRecursively(finalDir, overwrite = true)
        finalDir.walkTopDown().filter { it.isFile && (it.name == "bash" || it.name == "java" || it.name == "gradle" || it.name == "aapt2") }
            .forEach { it.setExecutable(true, true) }
        staging.deleteRecursively()
        val status = toolchainStatus()
        check(status.ready) { status.message }
    }

    fun toolchainStatus(): ToolchainStatus {
        val bash = File(toolchainDir, "bin/bash")
        val java = File(toolchainDir, "bin/java")
        val gradle = File(toolchainDir, "bin/gradle")
        val sdk = File(toolchainDir, "android-sdk")
        val platform = sdk.listFiles()?.any { it.name.startsWith("platforms") } == true ||
            File(sdk, "platforms").listFiles()?.isNotEmpty() == true
        return when {
            !bash.exists() -> ToolchainStatus(false, "Missing toolchain/bin/bash.")
            !java.exists() -> ToolchainStatus(false, "Missing toolchain/bin/java.")
            !gradle.exists() -> ToolchainStatus(false, "Missing toolchain/bin/gradle. Wrapper projects may still use gradlew once a compatible runtime is available.")
            !platform -> ToolchainStatus(false, "Android SDK platforms are missing from toolchain/android-sdk.")
            else -> ToolchainStatus(true, "Bash, Java, Gradle and Android SDK were detected. Build compatibility still depends on ARM64 tools and project requirements.")
        }
    }

    fun projectDir(projectId: String): File {
        require(projectId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid project ID" }
        val dir = File(projectsDir, projectId).canonicalFile
        require(dir.parentFile == projectsDir.canonicalFile && dir.isDirectory) { "Project not found" }
        return dir
    }

    fun listFiles(projectId: String): List<String> {
        val root = projectDir(projectId)
        return root.walkTopDown()
            .filter { it.isFile && it.name != ".andro-project.json" }
            .take(300)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }

    fun readFile(projectId: String, relativePath: String): String {
        val root = projectDir(projectId).canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Path escapes project directory" }
        require(file.isFile) { "File not found" }
        require(file.length() <= 512 * 1024) { "File exceeds 512 KB read limit" }
        return file.readText()
    }

    fun getProject(projectId: String): ProjectInfo =
        readProjectInfo(projectDir(projectId)) ?: error("Project metadata missing")

    private fun readProjectInfo(dir: File): ProjectInfo? = runCatching {
        val json = JSONObject(File(dir, ".andro-project.json").readText())
        ProjectInfo(
            json.getString("id"),
            json.getString("name"),
            dir.absolutePath,
            File(dir, "gradlew").exists()
        )
    }.getOrNull()

    private fun copyTree(source: DocumentFile, destination: File) {
        destination.mkdirs()
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name == ".git" || name == ".gradle" || name == "build") return@forEach
            val target = File(destination, name)
            if (child.isDirectory) copyTree(child, target)
            else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

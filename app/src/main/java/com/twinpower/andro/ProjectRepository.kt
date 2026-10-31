package com.twinpower.andro

import android.content.Context
import android.content.Intent
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

data class ToolchainStatus(
    val ready: Boolean,
    val message: String
)

class ProjectRepository(private val context: Context) {

    private val projectsDir =
        File(context.filesDir, "projects").apply { mkdirs() }

    val toolchainDir = File(context.filesDir, "toolchain")

    fun listProjects(): List<ProjectInfo> =
        projectsDir.listFiles()
            ?.filter {
                it.isDirectory && File(it, ".andro-project.json").exists()
            }
            ?.mapNotNull { readProjectInfo(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    suspend fun createProject(name: String): ProjectInfo =
        withContext(Dispatchers.IO) {
            val safeName = name.trim()

            require(
                safeName.matches(
                    Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,49}")
                )
            ) {
                "Use 1–50 letters, numbers, spaces, dots, underscores or hyphens."
            }

            val id = UUID.randomUUID().toString()
            val destination = File(projectsDir, id)

            check(destination.mkdirs()) {
                "Could not create project directory."
            }

            try {
                AndroidProjectTemplate.generate(destination, safeName)

                val metadata = JSONObject()
                    .put("id", id)
                    .put("name", safeName)
                    .put("createdAt", System.currentTimeMillis())

                File(destination, ".andro-project.json")
                    .writeText(metadata.toString(), Charsets.UTF_8)

                ProjectInfo(
                    id = id,
                    name = safeName,
                    rootPath = destination.absolutePath,
                    hasGradleWrapper = false
                )
            } catch (error: Exception) {
                destination.deleteRecursively()
                throw error
            }
        }

    suspend fun importProject(treeUri: Uri): ProjectInfo =
        withContext(Dispatchers.IO) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val source = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Could not open selected directory.")

            val id = UUID.randomUUID().toString()
            val safeName = source.name
                ?.takeIf { it.isNotBlank() }
                ?: "Android project"

            val destination = File(projectsDir, id)

            check(destination.mkdirs()) {
                "Could not create project directory."
            }

            try {
                copyTree(source, destination)

                val metadata = JSONObject()
                    .put("id", id)
                    .put("name", safeName)
                    .put("importedAt", System.currentTimeMillis())

                File(destination, ".andro-project.json")
                    .writeText(metadata.toString(), Charsets.UTF_8)

                ProjectInfo(
                    id,
                    safeName,
                    destination.absolutePath,
                    File(destination, "gradlew").exists()
                )
            } catch (error: Exception) {
                destination.deleteRecursively()
                throw error
            }
        }

    suspend fun importToolchain(treeUri: Uri) =
        withContext(Dispatchers.IO) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val source = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Could not open selected toolchain directory.")

            val staging = File(context.filesDir, "toolchain-import")
            val candidate = File(context.filesDir, "toolchain-candidate")
            val backup = File(context.filesDir, "toolchain-backup")

            staging.deleteRecursively()
            candidate.deleteRecursively()
            backup.deleteRecursively()

            check(staging.mkdirs()) {
                "Could not create toolchain staging directory."
            }

            try {
                copyTree(source, staging)

                val sourceRoot = when {
                    File(staging, "bin/bash").isFile -> staging

                    File(staging, "usr/bin/bash").isFile ->
                        File(staging, "usr")

                    else -> error(
                        "Toolchain must contain bin/bash or usr/bin/bash."
                    )
                }

                check(
                    sourceRoot.copyRecursively(
                        candidate,
                        overwrite = true
                    )
                ) {
                    "Could not stage the imported toolchain."
                }

                candidate.walkTopDown()
                    .filter {
                        it.isFile &&
                            it.name in setOf(
                                "bash",
                                "java",
                                "gradle",
                                "aapt2"
                            )
                    }
                    .forEach {
                        it.setExecutable(true, true)
                    }

                val status = inspectToolchain(candidate)

                check(status.ready) {
                    status.message
                }

                val current = toolchainDir

                if (current.exists()) {
                    check(current.renameTo(backup)) {
                        "Could not preserve the existing toolchain."
                    }
                }

                if (!candidate.renameTo(current)) {
                    if (backup.exists()) {
                        backup.renameTo(current)
                    }

                    error("Could not activate the imported toolchain.")
                }

                backup.deleteRecursively()
            } finally {
                staging.deleteRecursively()
                candidate.deleteRecursively()

                if (!toolchainDir.exists() && backup.exists()) {
                    backup.renameTo(toolchainDir)
                }
            }

            val status = toolchainStatus()

            check(status.ready) {
                status.message
            }
        }

    fun toolchainStatus(): ToolchainStatus =
        inspectToolchain(toolchainDir)

    private fun inspectToolchain(root: File): ToolchainStatus {
        val bash = File(root, "bin/bash")
        val java = File(root, "bin/java")
        val gradle = File(root, "bin/gradle")
        val sdk = File(root, "android-sdk")
        val platforms = File(sdk, "platforms")

        val hasPlatform =
            platforms.listFiles()?.any { it.isDirectory } == true

        return when {
            !bash.isFile ->
                ToolchainStatus(
                    false,
                    "Missing toolchain/bin/bash."
                )

            !java.isFile ->
                ToolchainStatus(
                    false,
                    "Missing toolchain/bin/java."
                )

            !gradle.isFile ->
                ToolchainStatus(
                    false,
                    "Missing toolchain/bin/gradle."
                )

            !hasPlatform ->
                ToolchainStatus(
                    false,
                    "Android SDK platforms are missing."
                )

            else ->
                ToolchainStatus(
                    true,
                    "Bash, Java, Gradle and Android SDK platform files detected. Runtime execution still needs verification."
                )
        }
    }

    fun projectDir(projectId: String): File {
        require(
            projectId.matches(
                Regex("[A-Za-z0-9-]{1,64}")
            )
        ) {
            "Invalid project ID."
        }

        val dir = File(projectsDir, projectId).canonicalFile

        require(
            dir.parentFile == projectsDir.canonicalFile &&
                dir.isDirectory
        ) {
            "Project not found."
        }

        return dir
    }

    fun listFiles(projectId: String): List<String> {
        val root = projectDir(projectId)

        return root.walkTopDown()
            .filter {
                it.isFile &&
                    it.name != ".andro-project.json"
            }
            .take(501)
            .map {
                it.relativeTo(root).invariantSeparatorsPath
            }
            .toList()
            .sorted()
    }

    fun readFile(
        projectId: String,
        relativePath: String
    ): String {
        val bytes = readFileBytes(projectId, relativePath)
        return bytes.toString(Charsets.UTF_8)
    }

    fun readFileBytes(
        projectId: String,
        relativePath: String
    ): ByteArray {
        val file = resolveProjectFile(projectId, relativePath)

        require(file.isFile) {
            "File not found."
        }

        require(file.length() <= 512 * 1024) {
            "File exceeds the 512 KB file limit."
        }

        return file.readBytes()
    }

    fun writeFile(
        projectId: String,
        relativePath: String,
        content: String
    ) {
        writeFileBytes(
            projectId,
            relativePath,
            content.toByteArray(Charsets.UTF_8)
        )
    }

    fun writeFileBytes(
        projectId: String,
        relativePath: String,
        content: ByteArray
    ) {
        require(content.size <= 512 * 1024) {
            "File exceeds the 512 KB file limit."
        }

        require(relativePath != ".andro-project.json") {
            "Project metadata cannot be edited."
        }

        val file = resolveProjectFile(projectId, relativePath)
        val parent = file.parentFile

        check(
            parent == null ||
                parent.exists() ||
                parent.mkdirs()
        ) {
            "Could not create parent directory."
        }

        val temporary = File(
            parent,
            ".${file.name}.${UUID.randomUUID()}.tmp"
        )

        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }

            if (file.exists() && !file.delete()) {
                error("Could not replace existing file.")
            }

            if (!temporary.renameTo(file)) {
                error("Could not move the completed file into place.")
            }
        } finally {
            temporary.delete()
        }
    }

    fun createDirectory(
        projectId: String,
        relativePath: String
    ) {
        val directory = resolveProjectFile(
            projectId,
            relativePath
        )

        check(directory.mkdirs()) {
            "Could not create directory. It may already exist."
        }
    }

    fun createFile(
        projectId: String,
        relativePath: String,
        content: String = ""
    ) {
        require(relativePath != ".andro-project.json") {
            "Reserved file name."
        }

        val file = resolveProjectFile(
            projectId,
            relativePath
        )

        file.parentFile?.mkdirs()

        check(file.createNewFile()) {
            "File already exists or could not be created."
        }

        file.writeText(content, Charsets.UTF_8)
    }

    fun deleteEntry(
        projectId: String,
        relativePath: String
    ) {
        require(relativePath != ".andro-project.json") {
            "Project metadata cannot be deleted."
        }

        val entry = resolveProjectFile(
            projectId,
            relativePath
        )

        check(entry.exists() && entry.deleteRecursively()) {
            "Could not delete project entry."
        }
    }

    fun getProject(projectId: String): ProjectInfo =
        readProjectInfo(projectDir(projectId))
            ?: error("Project metadata missing.")

    private fun resolveProjectFile(
        projectId: String,
        relativePath: String
    ): File {
        require(relativePath.isNotBlank()) {
            "File path cannot be empty."
        }

        require(relativePath.length <= 240) {
            "File path is too long."
        }

        require(!relativePath.startsWith("/")) {
            "Absolute paths are not allowed."
        }

        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')

        require(
            segments.none {
                it.isBlank() || it == "." || it == ".."
            }
        ) {
            "Invalid file path."
        }

        val root = projectDir(projectId).canonicalFile
        val file = File(root, normalized).canonicalFile

        require(
            file.path.startsWith(root.path + File.separator)
        ) {
            "Path escapes the project directory."
        }

        return file
    }

    private fun readProjectInfo(dir: File): ProjectInfo? =
        runCatching {
            val json = JSONObject(
                File(dir, ".andro-project.json")
                    .readText(Charsets.UTF_8)
            )

            ProjectInfo(
                id = json.getString("id"),
                name = json.getString("name"),
                rootPath = dir.absolutePath,
                hasGradleWrapper =
                    File(dir, "gradlew").exists()
            )
        }.getOrNull()

    private fun copyTree(
        source: DocumentFile,
        destination: File
    ) {
        check(
            destination.exists() || destination.mkdirs()
        ) {
            "Could not create destination directory."
        }

        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach

            if (name in setOf(".git", ".gradle", "build")) {
                return@forEach
            }

            val target = File(destination, name)

            if (child.isDirectory) {
                copyTree(child, target)
            } else if (child.isFile) {
                context.contentResolver
                    .openInputStream(child.uri)
                    ?.use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    ?: error("Could not read imported file: $name")
            }
        }
    }
}

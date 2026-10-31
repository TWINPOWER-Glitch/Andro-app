
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

/**
 * Additional persistent information about a local project.
 *
 * GitHub repository credentials are deliberately excluded.
 * Repository associations are managed by ProjectGitHubBindingStore.
 */
data class ProjectMetadata(
    val projectId: String,
    val source: String,
    val createdAt: Long,
    val importedAt: Long?,
    val lastOpenedAt: Long?,
    val lastSyncedAt: Long?,
    val syncState: String,
    val lastSyncMessage: String?
)

data class ToolchainStatus(
    val ready: Boolean,
    val message: String
)

class ProjectRepository(private val context: Context) {

    private val projectsDir =
        File(context.filesDir, "projects").apply { mkdirs() }

    val toolchainDir = File(context.filesDir, "toolchain")

    private val githubBindingStore =
        ProjectGitHubBindingStore(context.applicationContext)

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

                val createdAt = System.currentTimeMillis()

                val metadata = JSONObject()
                    .put("id", id)
                    .put("name", safeName)
                    .put("createdAt", createdAt)
                    .put("source", "local")
                    .put("syncState", "not_synced")

                File(destination, ".andro-project.json")
                    .writeText(metadata.toString(), Charsets.UTF_8)

                ProjectInfo(
                    id = id,
                    name = safeName,
                    rootPath = destination.absolutePath,
                    hasGradleWrapper = File(destination, "gradlew").exists()
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

            require(source.isDirectory) {
                "The selected location is not a directory."
            }

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

                val importedAt = System.currentTimeMillis()

                val metadata = JSONObject()
                    .put("id", id)
                    .put("name", safeName)
                    .put("importedAt", importedAt)
                    .put("createdAt", importedAt)
                    .put("source", "imported")
                    .put("syncState", "not_synced")

                File(destination, ".andro-project.json")
                    .writeText(metadata.toString(), Charsets.UTF_8)

                ProjectInfo(
                    id = id,
                    name = safeName,
                    rootPath = destination.absolutePath,
                    hasGradleWrapper = File(destination, "gradlew").exists()
                )
            } catch (error: Exception) {
                destination.deleteRecursively()
                throw error
            }
        }

    /**
     * Returns persisted metadata for a project.
     *
     * Older projects created before metadata tracking was introduced
     * receive safe defaults without requiring migration.
     */
    fun getMetadata(projectId: String): ProjectMetadata {
        val project = getProject(projectId)
        val metadataFile = File(
            projectDir(projectId),
            ".andro-project.json"
        )

        val json = JSONObject(
            metadataFile.readText(Charsets.UTF_8)
        )

        val createdAt = json.optLong(
            "createdAt",
            json.optLong("importedAt", 0L)
        )

        return ProjectMetadata(
            projectId = project.id,
            source = json.optString("source", "unknown"),
            createdAt = createdAt,
            importedAt = json.optLong("importedAt")
                .takeIf { it > 0L },
            lastOpenedAt = json.optLong("lastOpenedAt")
                .takeIf { it > 0L },
            lastSyncedAt = json.optLong("lastSyncedAt")
                .takeIf { it > 0L },
            syncState = json.optString(
                "syncState",
                "not_synced"
            ),
            lastSyncMessage = json.optString(
                "lastSyncMessage",
                ""
            ).takeIf { it.isNotBlank() }
        )
    }

    /**
     * Records when a project is opened in the workspace.
     */
    fun markProjectOpened(projectId: String) {
        updateMetadata(projectId) {
            it.put(
                "lastOpenedAt",
                System.currentTimeMillis()
            )
        }
    }

    /**
     * Marks the start of a GitHub synchronization operation.
     */
    fun markSyncStarted(projectId: String) {
        updateMetadata(projectId) {
            it.put("syncState", "syncing")
                .put("lastSyncMessage", "Synchronization started.")
        }
    }

    /**
     * Records a successful synchronization.
     */
    fun markSyncSucceeded(
        projectId: String,
        message: String = "Project synchronized successfully."
    ) {
        updateMetadata(projectId) {
            it.put("syncState", "synced")
                .put(
                    "lastSyncedAt",
                    System.currentTimeMillis()
                )
                .put("lastSyncMessage", message)
        }
    }

    /**
     * Records a failed synchronization without deleting
     * the previous successful synchronization timestamp.
     */
    fun markSyncFailed(
        projectId: String,
        message: String
    ) {
        require(message.length <= 1000) {
            "Synchronization message is too long."
        }

        updateMetadata(projectId) {
            it.put("syncState", "failed")
                .put("lastSyncMessage", message)
        }
    }

    /**
     * Changes the local project display name.
     * The project ID and directory remain unchanged.
     */
    fun updateProjectName(
        projectId: String,
        newName: String
    ): ProjectInfo {
        val safeName = newName.trim()

        require(
            safeName.matches(
                Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,49}")
            )
        ) {
            "Use 1–50 letters, numbers, spaces, dots, underscores or hyphens."
        }

        updateMetadata(projectId) {
            it.put("name", safeName)
        }

        return getProject(projectId)
    }

    /**
     * Returns the saved GitHub repository association for a project.
     *
     * The project must exist in the local Andro workspace.
     */
    fun getGitHubBinding(
        projectId: String
    ): ProjectGitHubBinding? {
        getProject(projectId)
        return githubBindingStore.get(projectId)
    }

    /**
     * Saves a GitHub repository association for a local project.
     *
     * Access tokens are not accepted or stored by this method.
     */
    fun saveGitHubBinding(
        binding: ProjectGitHubBinding
    ) {
        getProject(binding.projectId)
        githubBindingStore.save(binding)
    }

    /**
     * Removes the GitHub association without deleting
     * the local project or remote GitHub repository.
     */
    fun removeGitHubBinding(projectId: String) {
        getProject(projectId)
        githubBindingStore.remove(projectId)
    }

    /**
     * Updates project metadata while preserving all existing
     * metadata fields that are not explicitly changed.
     */
    @Synchronized
    private fun updateMetadata(
        projectId: String,
        update: (JSONObject) -> Unit
    ) {
        val directory = projectDir(projectId)
        val metadataFile = File(
            directory,
            ".andro-project.json"
        )

        check(metadataFile.isFile) {
            "Project metadata is missing."
        }

        val json = JSONObject(
            metadataFile.readText(Charsets.UTF_8)
        )

        require(json.optString("id") == projectId) {
            "Project metadata does not match its directory."
        }

        update(json)

        writeMetadataAtomically(metadataFile, json)
    }

    /**
     * Writes metadata through a temporary file and backup.
     * This prevents an interrupted metadata update from
     * leaving a partially written JSON document.
     */
    private fun writeMetadataAtomically(
        destination: File,
        json: JSONObject
    ) {
        val parent = destination.parentFile
            ?: error("Invalid project metadata location.")

        val temporary = File(
            parent,
            ".andro-project.${UUID.randomUUID()}.tmp"
        )

        val backup = File(
            parent,
            ".andro-project.${UUID.randomUUID()}.bak"
        )

        try {
            FileOutputStream(temporary).use { output ->
                output.write(
                    json.toString().toByteArray(Charsets.UTF_8)
                )
                output.fd.sync()
            }

            check(temporary.isFile && temporary.length() > 0L) {
                "Could not write project metadata."
            }

            check(destination.renameTo(backup)) {
                "Could not preserve existing project metadata."
            }

            if (!temporary.renameTo(destination)) {
                backup.renameTo(destination)
                error("Could not activate updated project metadata.")
            }

            backup.delete()
        } finally {
            temporary.delete()

            if (backup.exists() && !destination.exists()) {
                backup.renameTo(destination)
            }

            if (backup.exists() && destination.exists()) {
                backup.delete()
            }
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

        val root = projectsDir.canonicalFile
        val dir = File(root, projectId).canonicalFile

        require(
            dir.parentFile == root && dir.isDirectory
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
            ?: error("Invalid file destination.")

        check(parent.exists() || parent.mkdirs()) {
            "Could not create parent directory."
        }

        val temporary = File(
            parent,
            ".${file.name}.${UUID.randomUUID()}.tmp"
        )

        val backup = File(
            parent,
            ".${file.name}.${UUID.randomUUID()}.bak"
        )

        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }

            if (file.exists()) {
                check(file.isFile) {
                    "The destination exists and is not a file."
                }

                check(file.renameTo(backup)) {
                    "Could not preserve the existing file."
                }
            }

            if (!temporary.renameTo(file)) {
                if (backup.exists()) {
                    backup.renameTo(file)
                }

                error("Could not move the completed file into place.")
            }

            backup.delete()
        } finally {
            temporary.delete()

            if (backup.exists() && !file.exists()) {
                backup.renameTo(file)
            }

            if (backup.exists() && file.exists()) {
                backup.delete()
            }
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

        val parent = file.parentFile
            ?: error("Invalid file destination.")

        check(parent.exists() || parent.mkdirs()) {
            "Could not create parent directory."
        }

        check(file.createNewFile()) {
            "File already exists or could not be created."
        }

        try {
            FileOutputStream(file).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
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

            val id = json.getString("id")

            require(id == dir.name) {
                "Project metadata ID does not match its directory."
            }

            ProjectInfo(
                id = id,
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

        val canonicalDestination = destination.canonicalFile

        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach

            if (name in setOf(".git", ".gradle", "build")) {
                return@forEach
            }

            require(
                name != "." &&
                    name != ".." &&
                    !name.contains('/') &&
                    !name.contains('\\')
            ) {
                "Imported entry has an invalid name."
            }

            val target = File(destination, name).canonicalFile

            require(
                target.parentFile == canonicalDestination
            ) {
                "Imported entry escapes the destination directory."
            }

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

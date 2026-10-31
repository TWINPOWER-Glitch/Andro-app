
package com.twinpower.andro

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubSyncResult(
    val operation: String,
    val filesProcessed: Int,
    val branch: String,
    val message: String
)

class GitHubProjectSync(
    private val repository: ProjectRepository,
    private val tokenProvider: () -> String?
) {
    companion object {
        private const val API = "https://api.github.com"
        private const val MAX_FILE_SIZE = 512 * 1024
        private const val MAX_FILES = 500
    }

    private fun token(): String =
        tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("GitHub is not connected.")

    private fun validate(owner: String, repo: String, branch: String) {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Invalid GitHub owner."
        }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Invalid repository name."
        }
        require(branch.isNotBlank() && branch.length <= 255) {
            "Invalid branch."
        }
    }

    private fun api(
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): String {
        val connection = URL("$API$path")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${token()}"
            )
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )
            connection.setRequestProperty(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Andro-Android-App"
            )

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optString("message")
                }.getOrDefault(response)

                throw IOException("GitHub HTTP $status: $detail")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun repoPath(owner: String, repo: String) =
        "/repos/$owner/$repo"

    /**
     * Pulls the selected GitHub branch into the local Andro project.
     *
     * Existing local files with matching paths are overwritten.
     * Files absent from GitHub are not deleted locally.
     */
    suspend fun pull(
        project: ProjectInfo,
        owner: String,
        repo: String,
        branch: String
    ): GitHubSyncResult = withContext(Dispatchers.IO) {
        validate(owner, repo, branch)

        val root = repoPath(owner, repo)
        val branchInfo = JSONObject(
            api("$root/branches/${encode(branch)}")
        )

        val commitSha = branchInfo
            .getJSONObject("commit")
            .getString("sha")

        val commit = JSONObject(
            api("$root/git/commits/$commitSha")
        )

        val treeSha = commit
            .getJSONObject("tree")
            .getString("sha")

        val treeResponse = JSONObject(
            api("$root/git/trees/$treeSha?recursive=1")
        )

        if (treeResponse.optBoolean("truncated")) {
            throw IOException(
                "GitHub tree is too large for a complete sync."
            )
        }

        val tree = treeResponse.optJSONArray("tree")
            ?: JSONArray()

        var processed = 0

        for (index in 0 until tree.length()) {
            val item = tree.getJSONObject(index)

            if (item.optString("type") != "blob") continue

            val path = item.optString("path")
            val size = item.optLong("size")

            if (path.isBlank()) continue
            if (path == ".andro-project.json") continue
            if (path.startsWith(".git/")) continue
            if (path.startsWith(".gradle/")) continue
            if (path.split("/").any {
                    it == "build" || it == ".git"
                }) continue

            require(!path.startsWith("/") && !path.contains("\\") &&
                path.split("/").none {
                    it.isBlank() || it == "." || it == ".."
                }) {
                "Unsafe repository path: $path"
            }

            if (size > MAX_FILE_SIZE) {
                continue
            }

            if (processed >= MAX_FILES) {
                throw IOException(
                    "Project exceeds the $MAX_FILES file sync limit."
                )
            }

            val blobSha = item.optString("sha")
            if (blobSha.isBlank()) continue

            val blob = JSONObject(
                api("$root/git/blobs/$blobSha")
            )

            require(blob.optString("encoding") == "base64") {
                "Unsupported GitHub blob encoding for $path."
            }

            val encoded = blob.optString("content")
                .replace("\\n", "")
                .replace("\\r", "")
                .filterNot { it.isWhitespace() }

            val bytes = Base64.decode(encoded, Base64.DEFAULT)

            require(bytes.size <= MAX_FILE_SIZE) {
                "File exceeds the 512 KB limit: $path"
            }

            val content = String(bytes, Charsets.UTF_8)

            repository.writeFile(
                project.id,
                path,
                content
            )

            processed++
        }

        GitHubSyncResult(
            operation = "pull",
            filesProcessed = processed,
            branch = branch,
            message = "Pulled $processed files from $owner/$repo ($branch)."
        )
    }

    /**
     * Pushes local project files to the selected GitHub branch.
     *
     * Each file is committed independently through GitHub's Contents API.
     * The operation is not atomic: if one file fails, earlier files remain
     * committed and the exception identifies the failed operation.
     */
    suspend fun push(
        project: ProjectInfo,
        owner: String,
        repo: String,
        branch: String,
        commitMessage: String
    ): GitHubSyncResult = withContext(Dispatchers.IO) {
        validate(owner, repo, branch)

        require(commitMessage.isNotBlank()) {
            "A commit message is required."
        }

        val files = repository.listFiles(project.id)
            .filter { path ->
                path != ".andro-project.json" &&
                    !path.startsWith(".git/") &&
                    !path.startsWith(".gradle/") &&
                    path.split("/").none {
                        it == "build" || it == ".git"
                    }
            }

        if (files.size > MAX_FILES) {
            throw IOException(
                "Project exceeds the $MAX_FILES file push limit."
            )
        }

        var processed = 0
        val root = repoPath(owner, repo)

        for (path in files) {
            require(!path.startsWith("/") && !path.contains("\\") &&
                path.split("/").none {
                    it.isBlank() || it == "." || it == ".."
                }) {
                "Unsafe local project path: $path"
            }

            val content = repository.readFile(project.id, path)
            val bytes = content.toByteArray(Charsets.UTF_8)

            require(bytes.size <= MAX_FILE_SIZE) {
                "File exceeds the 512 KB push limit: $path"
            }

            val encodedPath = path.split("/")
                .joinToString("/") { encode(it) }

            val existing = runCatching {
                JSONObject(
                    api(
                        "$root/contents/$encodedPath?ref=${encode(branch)}"
                    )
                ).optString("sha")
            }.getOrNull()

            val payload = JSONObject()
                .put("message", commitMessage)
                .put(
                    "content",
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
                .put("branch", branch)

            if (!existing.isNullOrBlank()) {
                payload.put("sha", existing)
            }

            api(
                "$root/contents/$encodedPath",
                method = "PUT",
                body = payload
            )

            processed++
        }

        GitHubSyncResult(
            operation = "push",
            filesProcessed = processed,
            branch = branch,
            message = "Pushed $processed files to $owner/$repo ($branch)."
        )
    }
}

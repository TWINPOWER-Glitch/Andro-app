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

    private fun validate(
        owner: String,
        repo: String,
        branch: String
    ) {
        require(
            owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))
        ) {
            "Invalid GitHub owner."
        }

        require(
            repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))
        ) {
            "Invalid repository name."
        }

        require(
            branch.isNotBlank() &&
                branch.length <= 255 &&
                !branch.contains('\n')
        ) {
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
                    it.write(
                        body.toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response)
                        .optString("message")
                        .ifBlank { response }
                }.getOrDefault(response)

                throw GitHubApiException(status, detail)
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(
            value,
            Charsets.UTF_8.name()
        ).replace("+", "%20")

    private fun repoPath(
        owner: String,
        repo: String
    ) = "/repos/$owner/$repo"

    private fun shouldSync(path: String): Boolean {
        if (
            path.isBlank() ||
            path == ".andro-project.json"
        ) {
            return false
        }

        val segments = path.split("/")

        return segments.none {
            it == ".git" ||
                it == ".gradle" ||
                it == "build"
        }
    }

    private fun validatePath(path: String) {
        require(
            !path.startsWith("/") &&
                !path.contains('\\')
        ) {
            "Unsafe repository path: $path"
        }

        require(
            path.split("/").none {
                it.isBlank() ||
                    it == "." ||
                    it == ".."
            }
        ) {
            "Unsafe repository path: $path"
        }
    }

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

        val eligible = (0 until tree.length())
            .map {
                tree.getJSONObject(it)
            }
            .filter {
                it.optString("type") == "blob" &&
                    shouldSync(it.optString("path"))
            }

        if (eligible.size > MAX_FILES) {
            throw IOException(
                "Project exceeds the $MAX_FILES file sync limit."
            )
        }

        var processed = 0

        for (item in eligible) {
            val path = item.optString("path")

            validatePath(path)

            val size = item.optLong("size", -1L)

            if (size < 0L) {
                throw IOException(
                    "GitHub did not provide a valid size for $path"
                )
            }

            if (size > MAX_FILE_SIZE) {
                throw IOException(
                    "File exceeds the 512 KB sync limit: $path"
                )
            }

            val blobSha = item.optString("sha")

            if (blobSha.isBlank()) {
                throw IOException(
                    "Missing GitHub blob SHA for $path"
                )
            }

            val blob = JSONObject(
                api("$root/git/blobs/$blobSha")
            )

            if (blob.optString("encoding") != "base64") {
                throw IOException(
                    "Unsupported GitHub blob encoding for $path."
                )
            }

            val encoded = blob.optString("content")
                .replace("\\n", "")
                .replace("\\r", "")
                .filterNot { it.isWhitespace() }

            val bytes = try {
                Base64.decode(
                    encoded,
                    Base64.DEFAULT
                )
            } catch (error: IllegalArgumentException) {
                throw IOException(
                    "Invalid Base64 content returned for $path",
                    error
                )
            }

            if (bytes.size > MAX_FILE_SIZE) {
                throw IOException(
                    "File exceeds the 512 KB sync limit: $path"
                )
            }

            repository.writeFileBytes(
                project.id,
                path,
                bytes
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

        val files = repository
            .listFiles(project.id)
            .filter(::shouldSync)

        if (files.size > MAX_FILES) {
            throw IOException(
                "Project exceeds the $MAX_FILES file push limit."
            )
        }

        val root = repoPath(owner, repo)

        var processed = 0

        for (path in files) {
            validatePath(path)

            val bytes = repository.readFileBytes(
                project.id,
                path
            )

            if (bytes.size > MAX_FILE_SIZE) {
                throw IOException(
                    "File exceeds the 512 KB push limit: $path"
                )
            }

            val encodedPath = path.split("/")
                .joinToString("/") {
                    encode(it)
                }

            val existingSha = try {
                JSONObject(
                    api(
                        "$root/contents/$encodedPath?ref=${encode(branch)}"
                    )
                )
                    .optString("sha")
                    .takeIf { it.isNotBlank() }
            } catch (error: GitHubApiException) {
                if (error.statusCode == 404) {
                    null
                } else {
                    throw error
                }
            }

            val payload = JSONObject()
                .put("message", commitMessage)
                .put(
                    "content",
                    Base64.encodeToString(
                        bytes,
                        Base64.NO_WRAP
                    )
                )
                .put("branch", branch)

            if (existingSha != null) {
                payload.put("sha", existingSha)
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

    private class GitHubApiException(
        val statusCode: Int,
        message: String
    ) : IOException(
        "GitHub HTTP $statusCode: $message"
    )
}

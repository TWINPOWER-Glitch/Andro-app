
package com.twinpower.andro

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType =
        "application/json; charset=utf-8".toMediaType()

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
                !branch.startsWith("/") &&
                !branch.endsWith("/") &&
                !branch.contains("..") &&
                !branch.contains("//") &&
                !branch.contains("@{") &&
                branch.none {
                    it.isWhitespace() ||
                        it.isISOControl() ||
                        it in setOf('~', '^', ':', '?', '*', '[', '\\')
                }
        ) {
            "Invalid branch."
        }
    }

    private fun api(
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): String {
        val builder = Request.Builder()
            .url("$API$path")
            .header(
                "Authorization",
                "Bearer ${token()}"
            )
            .header(
                "Accept",
                "application/vnd.github+json"
            )
            .header(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )
            .header(
                "User-Agent",
                "Andro-Android-App"
            )

        val requestBody = body?.toString()
            ?.toRequestBody(jsonMediaType)

        val request = builder
            .method(method, requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body
                ?.string()
                .orEmpty()

            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(responseText)
                        .optString("message")
                        .ifBlank { responseText }
                }.getOrDefault(responseText)

                throw GitHubApiException(
                    response.code,
                    detail
                )
            }

            return responseText
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
            path.isNotBlank() &&
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

        require(path.length <= 240) {
            "Repository path is too long: $path"
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
            .map { tree.getJSONObject(it) }
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

        require(commitMessage.length <= 500) {
            "Commit message is too long."
        }

        val files = repository
            .listFiles(project.id)
            .filter(::shouldSync)

        if (files.size > MAX_FILES) {
            throw IOException(
                "Project exceeds the $MAX_FILES file push limit."
            )
        }

        if (files.isEmpty()) {
            throw IOException(
                "There are no project files available to push."
            )
        }

        val root = repoPath(owner, repo)

        // Read the current branch reference before creating a commit.
        val reference = JSONObject(
            api("$root/git/ref/heads/${encode(branch)}")
        )

        val parentCommitSha = reference
            .getJSONObject("object")
            .getString("sha")

        val parentCommit = JSONObject(
            api("$root/git/commits/$parentCommitSha")
        )

        val baseTreeSha = parentCommit
            .getJSONObject("tree")
            .getString("sha")

        val treeEntries = JSONArray()

        var processed = 0

        // Upload each project file as a Git blob.
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

            val encodedContent = Base64.encodeToString(
                bytes,
                Base64.NO_WRAP
            )

            val blobPayload = JSONObject()
                .put("content", encodedContent)
                .put("encoding", "base64")

            val blob = JSONObject(
                api(
                    "$root/git/blobs",
                    method = "POST",
                    body = blobPayload
                )
            )

            val blobSha = blob.optString("sha")

            if (blobSha.isBlank()) {
                throw IOException(
                    "GitHub did not return a blob SHA for $path."
                )
            }

            treeEntries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", if (path == "gradlew") "100755" else "100644")
                    .put("type", "blob")
                    .put("sha", blobSha)
            )

            processed++
        }

        // Create one tree containing the complete project update.
        val treePayload = JSONObject()
            .put("base_tree", baseTreeSha)
            .put("tree", treeEntries)

        val createdTree = JSONObject(
            api(
                "$root/git/trees",
                method = "POST",
                body = treePayload
            )
        )

        val newTreeSha = createdTree.optString("sha")

        if (newTreeSha.isBlank()) {
            throw IOException(
                "GitHub did not return the new project tree SHA."
            )
        }

        // Create one commit for the entire project update.
        val parents = JSONArray().put(parentCommitSha)

        val commitPayload = JSONObject()
            .put("message", commitMessage)
            .put("tree", newTreeSha)
            .put("parents", parents)

        val createdCommit = JSONObject(
            api(
                "$root/git/commits",
                method = "POST",
                body = commitPayload
            )
        )

        val newCommitSha = createdCommit.optString("sha")

        if (newCommitSha.isBlank()) {
            throw IOException(
                "GitHub did not return the new commit SHA."
            )
        }

        // Move the branch only if it still points to the commit
        // that was read at the beginning of this operation.
        val updatePayload = JSONObject()
            .put("sha", newCommitSha)
            .put("force", false)

        api(
            "$root/git/refs/heads/${encode(branch)}",
            method = "PATCH",
            body = updatePayload
        )

        GitHubSyncResult(
            operation = "push",
            filesProcessed = processed,
            branch = branch,
            message = "Pushed $processed files in one commit to $owner/$repo ($branch). Commit: $newCommitSha"
        )
    }

    private class GitHubApiException(
        val statusCode: Int,
        message: String
    ) : IOException(
        "GitHub HTTP $statusCode: $message"
    )
}

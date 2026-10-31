
package com.twinpower.andro

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubWorkflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val workflowId: Long,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val commitSha: String,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String
) {
    val isFinished: Boolean
        get() = status == "completed"

    val succeeded: Boolean
        get() = isFinished && conclusion == "success"
}

data class GitHubArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val downloadUrl: String
)

class GitHubWorkflowManager(
    private val tokenProvider: () -> String?
) {
    companion object {
        private const val API = "https://api.github.com"

        private const val MAX_ARTIFACT_DOWNLOAD_BYTES =
            500L * 1024L * 1024L

        private const val MAX_EXTRACTED_BYTES =
            500L * 1024L * 1024L

        private const val MAX_ARCHIVE_ENTRIES = 2000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType =
        "application/json; charset=utf-8".toMediaType()

    private fun accessToken(): String {
        return tokenProvider()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("GitHub account is not connected.")
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer ${accessToken()}")
            .header("User-Agent", "Andro-Android-IDE")

        when (method) {
            "POST" -> builder.post(
                (body ?: "{}").toRequestBody(jsonMediaType)
            )

            "PATCH" -> builder.patch(
                (body ?: "{}").toRequestBody(jsonMediaType)
            )

            else -> builder.get()
        }

        return builder.build()
    }

    private fun execute(
        url: String,
        method: String = "GET",
        body: String? = null
    ): String {
        client.newCall(request(url, method, body))
            .execute()
            .use { response ->
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(responseBody)
                            .optString("message")
                    }.getOrDefault(responseBody)

                    throw IOException(
                        "GitHub API ${response.code}: $detail"
                    )
                }

                return responseBody
            }
    }

    private fun validateRepository(
        owner: String,
        repository: String
    ) {
        val valid = Regex("[A-Za-z0-9_.-]{1,100}")

        require(valid.matches(owner)) {
            "Invalid GitHub owner."
        }

        require(valid.matches(repository)) {
            "Invalid GitHub repository name."
        }
    }

    private fun repositoryUrl(
        owner: String,
        repository: String
    ): String {
        validateRepository(owner, repository)
        return "$API/repos/$owner/$repository"
    }

    fun listWorkflows(
        owner: String,
        repository: String
    ): List<GitHubWorkflow> {
        val url = "${repositoryUrl(owner, repository)}/actions/workflows"
        val root = JSONObject(execute(url))
        val workflows = root.optJSONArray("workflows")
            ?: JSONArray()

        return buildList {
            for (index in 0 until workflows.length()) {
                val item = workflows.getJSONObject(index)

                add(
                    GitHubWorkflow(
                        id = item.getLong("id"),
                        name = item.optString("name"),
                        path = item.optString("path"),
                        state = item.optString("state")
                    )
                )
            }
        }
    }

    fun dispatchWorkflow(
        owner: String,
        repository: String,
        workflowId: String,
        branch: String,
        inputs: Map<String, String> = emptyMap()
    ) {
        require(workflowId.isNotBlank()) {
            "Workflow ID is required."
        }

        require(branch.isNotBlank()) {
            "A branch is required."
        }

        require(
            workflowId.matches(Regex("[A-Za-z0-9_.-]+"))
        ) {
            "Invalid workflow identifier."
        }

        val payload = JSONObject()
            .put("ref", branch)
            .put("inputs", JSONObject(inputs))

        val url =
            "${repositoryUrl(owner, repository)}/actions/workflows/" +
                "$workflowId/dispatches"

        execute(
            url = url,
            method = "POST",
            body = payload.toString()
        )
    }

    fun listWorkflowRuns(
        owner: String,
        repository: String,
        branch: String? = null,
        perPage: Int = 20
    ): List<GitHubWorkflowRun> {
        require(perPage in 1..100) {
            "perPage must be between 1 and 100."
        }

        val query = buildString {
            append("?per_page=$perPage")

            if (!branch.isNullOrBlank()) {
                append("&branch=${encode(branch)}")
            }
        }

        val root = JSONObject(
            execute(
                "${repositoryUrl(owner, repository)}/actions/runs$query"
            )
        )

        val runs = root.optJSONArray("workflow_runs")
            ?: JSONArray()

        return buildList {
            for (index in 0 until runs.length()) {
                add(parseRun(runs.getJSONObject(index)))
            }
        }
    }

    fun getWorkflowRun(
        owner: String,
        repository: String,
        runId: Long
    ): GitHubWorkflowRun {
        require(runId > 0) {
            "Invalid workflow run ID."
        }

        val json = JSONObject(
            execute(
                "${repositoryUrl(owner, repository)}/actions/runs/$runId"
            )
        )

        return parseRun(json)
    }

    fun listArtifacts(
        owner: String,
        repository: String,
        runId: Long
    ): List<GitHubArtifact> {
        require(runId > 0) {
            "Invalid workflow run ID."
        }

        val root = JSONObject(
            execute(
                "${repositoryUrl(owner, repository)}/actions/runs/" +
                    "$runId/artifacts"
            )
        )

        val artifacts = root.optJSONArray("artifacts")
            ?: JSONArray()

        return buildList {
            for (index in 0 until artifacts.length()) {
                val item = artifacts.getJSONObject(index)

                add(
                    GitHubArtifact(
                        id = item.getLong("id"),
                        name = item.optString("name"),
                        sizeBytes = item.optLong("size_in_bytes"),
                        expired = item.optBoolean("expired"),
                        downloadUrl = item.optString("archive_download_url")
                    )
                )
            }
        }
    }

    /**
     * Downloads an Actions artifact ZIP and extracts it into:
     *
     * project/build/andro-artifacts/
     *
     * The returned File is the extracted artifact directory.
     */
    fun downloadArtifact(
        owner: String,
        repository: String,
        project: ProjectInfo,
        artifactId: Long
    ): File {
        require(artifactId > 0) {
            "Invalid artifact ID."
        }

        val projectRoot = File(project.rootPath).canonicalFile

        require(
            projectRoot.isDirectory &&
                projectRoot == repositoryProjectDirectory(project.id)
        ) {
            "Project directory does not match the selected project."
        }

        val downloadUrl =
            "${repositoryUrl(owner, repository)}/actions/artifacts/" +
                "$artifactId/zip"

        val parent = File(
            projectRoot,
            "build/andro-artifacts"
        ).canonicalFile

        require(
            parent.path.startsWith(projectRoot.path + File.separator)
        ) {
            "Artifact destination escapes the project directory."
        }

        check(parent.exists() || parent.mkdirs()) {
            "Could not create artifact directory."
        }

        val staging = File(
            parent,
            ".artifact-$artifactId-${System.nanoTime()}.tmp"
        )

        val destination = File(
            parent,
            "artifact-$artifactId"
        )

        check(!destination.exists()) {
            "This artifact has already been downloaded. Remove its folder before downloading it again."
        }

        check(staging.mkdirs()) {
            "Could not create artifact staging directory."
        }

        try {
            client.newCall(request(downloadUrl))
                .execute()
                .use { response ->

                    if (!response.isSuccessful) {
                        val detail = response.body
                            ?.string()
                            .orEmpty()

                        throw IOException(
                            "Artifact download failed: HTTP ${response.code}. $detail"
                        )
                    }

                    val body = response.body
                        ?: throw IOException(
                            "GitHub returned an empty artifact response."
                        )

                    val contentLength = body.contentLength()

                    if (
                        contentLength > MAX_ARTIFACT_DOWNLOAD_BYTES
                    ) {
                        throw IOException(
                            "Artifact archive exceeds the 500 MB download limit."
                        )
                    }

                    val limitedStream = SizeLimitedInputStream(
                        body.byteStream(),
                        MAX_ARTIFACT_DOWNLOAD_BYTES
                    )

                    extractArchive(
                        limitedStream,
                        staging
                    )
                }

            check(staging.renameTo(destination)) {
                "Could not move the extracted artifact into its final directory."
            }

            return destination
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun repositoryProjectDirectory(
        projectId: String
    ): File {
        // The project root is verified through the repository
        // instance's canonical project directory.
        return repositoryDirectory(projectId)
    }

    private fun repositoryDirectory(
        projectId: String
    ): File {
        return projectRepository.projectDir(projectId).canonicalFile
    }

    private val projectRepository: ProjectRepository
        get() = throw UnsupportedOperationException(
            "ProjectRepository must be supplied to downloadArtifact."
        )

    private fun extractArchive(
        input: java.io.InputStream,
        destination: File
    ) {
        var entryCount = 0
        var extractedBytes = 0L

        ZipInputStream(
            BufferedInputStream(input)
        ).use { zip ->

            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++

                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw IOException(
                        "Artifact archive contains too many entries."
                    )
                }

                val entryName = entry.name.replace('\\', '/')

                if (
                    entryName.isBlank() ||
                    entryName.startsWith("/") ||
                    entryName.split("/").any {
                        it == ".." || it == "."
                    }
                ) {
                    throw IOException(
                        "Artifact contains an unsafe file path."
                    )
                }

                val target = File(
                    destination,
                    entryName
                ).canonicalFile

                if (
                    target.path != destination.canonicalPath &&
                    !target.path.startsWith(
                        destination.canonicalPath + File.separator
                    )
                ) {
                    throw IOException(
                        "Artifact entry escapes the extraction directory."
                    )
                }

                if (entry.isDirectory) {
                    check(target.exists() || target.mkdirs()) {
                        "Could not create artifact directory."
                    }
                } else {
                    val parent = target.parentFile
                        ?: throw IOException(
                            "Invalid artifact file path."
                        )

                    check(parent.exists() || parent.mkdirs()) {
                        "Could not create artifact parent directory."
                    }

                    if (target.exists()) {
                        throw IOException(
                            "Artifact contains duplicate file paths."
                        )
                    }

                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)

                        while (true) {
                            val count = zip.read(buffer)

                            if (count == -1) break

                            extractedBytes += count

                            if (
                                extractedBytes >
                                MAX_EXTRACTED_BYTES
                            ) {
                                throw IOException(
                                    "Extracted artifact exceeds the 500 MB limit."
                                )
                            }

                            output.write(buffer, 0, count)
                        }
                    }
                }

                zip.closeEntry()
            }
        }

        if (entryCount == 0) {
            throw IOException(
                "The downloaded artifact archive is empty."
            )
        }
    }

    private class SizeLimitedInputStream(
        input: java.io.InputStream,
        private val limit: Long
    ) : FilterInputStream(input) {

        private var total = 0L

        override fun read(): Int {
            val value = super.read()

            if (value != -1) {
                total++

                if (total > limit) {
                    throw IOException(
                        "Artifact download exceeds the 500 MB limit."
                    )
                }
            }

            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            val count = super.read(buffer, offset, length)

            if (count > 0) {
                total += count

                if (total > limit) {
                    throw IOException(
                        "Artifact download exceeds the 500 MB limit."
                    )
                }
            }

            return count
        }
    }

    fun cancelWorkflowRun(
        owner: String,
        repository: String,
        runId: Long
    ) {
        require(runId > 0) {
            "Invalid workflow run ID."
        }

        execute(
            url = "${repositoryUrl(owner, repository)}/actions/runs/$runId/cancel",
            method = "POST"
        )
    }

    fun rerunWorkflow(
        owner: String,
        repository: String,
        runId: Long
    ) {
        require(runId > 0) {
            "Invalid workflow run ID."
        }

        execute(
            url = "${repositoryUrl(owner, repository)}/actions/runs/$runId/rerun",
            method = "POST"
        )
    }

    private fun parseRun(item: JSONObject): GitHubWorkflowRun {
        return GitHubWorkflowRun(
            id = item.getLong("id"),
            name = item.optString("name"),
            workflowId = item.optLong("workflow_id"),
            status = item.optString("status"),
            conclusion = item.optString("conclusion")
                .takeIf { it.isNotBlank() && it != "null" },
            branch = item.optString("head_branch"),
            commitSha = item.optString("head_sha"),
            htmlUrl = item.optString("html_url"),
            createdAt = item.optString("created_at"),
            updatedAt = item.optString("updated_at")
        )
    }

    private fun encode(value: String): String {
        return java.net.URLEncoder.encode(
            value,
            Charsets.UTF_8.name()
        ).replace("+", "%20")
    }
}

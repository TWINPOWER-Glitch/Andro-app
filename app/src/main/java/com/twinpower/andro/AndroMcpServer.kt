
package com.twinpower.andro

import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class AndroMcpServer(
    private val projects: ProjectRepository,
    private val builds: BuildManager,
    private val githubBuilds: GitHubMcpBuildService
) {

    @Volatile
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (server != null) return

        server = embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = 8765
        ) {
            routing {

                post("/mcp") {
                    val request = runCatching {
                        JSONObject(call.receiveText())
                    }.getOrElse {
                        call.respondText(
                            error(
                                null,
                                -32700,
                                "Invalid JSON"
                            ).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }

                    val method = request.optString("method")
                    val id = request.opt("id")
                    val params =
                        request.optJSONObject("params") ?: JSONObject()

                    if (method.startsWith("notifications/")) {
                        call.respond(HttpStatusCode.Accepted)
                        return@post
                    }

                    val response = when (method) {

                        "initialize" -> result(
                            id,
                            JSONObject()
                                .put(
                                    "protocolVersion",
                                    "2025-03-26"
                                )
                                .put(
                                    "capabilities",
                                    JSONObject().put(
                                        "tools",
                                        JSONObject()
                                    )
                                )
                                .put(
                                    "serverInfo",
                                    JSONObject()
                                        .put("name", "andro-local")
                                        .put("version", "0.6.0")
                                )
                        )

                        "ping" -> result(
                            id,
                            JSONObject()
                        )

                        "tools/list" -> result(
                            id,
                            JSONObject().put(
                                "tools",
                                tools()
                            )
                        )

                        "tools/call" -> callTool(
                            id,
                            params
                        )

                        else -> error(
                            id,
                            -32601,
                            "Method not found: $method"
                        )
                    }

                    call.respondText(
                        response.toString(),
                        ContentType.Application.Json
                    )
                }

                get("/health") {
                    call.respondText(
                        JSONObject()
                            .put("service", "andro-local-mcp")
                            .put("status", "ok")
                            .put("buildEngine", "github-actions")
                            .put("mcpVersion", "0.6.0")
                            .toString(),
                        ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
    }

    @Synchronized
    fun stop() {
        server?.stop(300, 500)
        server = null
    }

    private fun tools(): JSONArray {
        val list = JSONArray()

        list.put(
            tool(
                "andro_list_projects",
                "List projects imported into the local Andro workspace.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject())
            )
        )

        list.put(
            tool(
                "andro_get_project",
                "Get metadata for a local Android project.",
                objectSchema("projectId")
            )
        )

        list.put(
            tool(
                "andro_list_project_files",
                "List files in a local Android project.",
                objectSchema("projectId")
            )
        )

        list.put(
            tool(
                "andro_read_project_file",
                "Read a UTF-8 project file. Maximum file size is 512 KB.",
                objectSchema("projectId", "path")
            )
        )

        list.put(
            tool(
                "andro_build_project",
                "Push the selected local project to its saved GitHub repository, dispatch its configured GitHub Actions workflow, and locate the resulting workflow run ID. Requires GitHub authentication and a saved repository configuration.",
                buildProjectSchema()
            )
        )

        list.put(
            tool(
                "andro_get_build_status",
                "Get a legacy local build request record. This does not query live GitHub Actions run status.",
                objectSchema("buildId")
            )
        )

        list.put(
            tool(
                "andro_get_remote_build_status",
                "Get the actual GitHub Actions workflow run status and available artifacts for a locally managed project.",
                remoteBuildStatusSchema()
            )
        )

        return list
    }

    private fun tool(
        name: String,
        description: String,
        schema: JSONObject
    ) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)

    private fun objectSchema(
        vararg required: String
    ): JSONObject {

        val props = JSONObject()

        required.forEach { name ->
            props.put(
                name,
                JSONObject().put("type", "string")
            )
        }

        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required))
    }

    private fun buildProjectSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "projectId",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "The ID of the local Andro project to build."
                    )
            )
            .put(
                "commitMessage",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Optional Git commit message for the project synchronization."
                    )
            )

        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("projectId"))
    }

    private fun remoteBuildStatusSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "projectId",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "The ID of the local Andro project associated with the GitHub repository."
                    )
            )
            .put(
                "runId",
                JSONObject()
                    .put("type", "integer")
                    .put(
                        "description",
                        "The GitHub Actions workflow run ID returned by andro_build_project."
                    )
            )

        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put(
                "required",
                JSONArray()
                    .put("projectId")
                    .put("runId")
            )
    }

    private suspend fun callTool(
        id: Any?,
        params: JSONObject
    ): JSONObject {

        val name = params.optString("name")

        val args =
            params.optJSONObject("arguments") ?: JSONObject()

        return try {

            val data: Any = when (name) {

                "andro_list_projects" -> JSONArray().also { array ->

                    projects.listProjects().forEach { project ->

                        array.put(
                            JSONObject()
                                .put("id", project.id)
                                .put("name", project.name)
                                .put(
                                    "hasGradleWrapper",
                                    project.hasGradleWrapper
                                )
                        )
                    }
                }

                "andro_get_project" -> {

                    val project = projects.getProject(
                        args.getString("projectId")
                    )

                    JSONObject()
                        .put("id", project.id)
                        .put("name", project.name)
                        .put("rootPath", project.rootPath)
                        .put(
                            "hasGradleWrapper",
                            project.hasGradleWrapper
                        )
                }

                "andro_list_project_files" -> {

                    JSONArray(
                        projects.listFiles(
                            args.getString("projectId")
                        )
                    )
                }

                "andro_read_project_file" -> {

                    val projectId = args.getString("projectId")
                    val path = args.getString("path")

                    JSONObject()
                        .put("path", path)
                        .put(
                            "content",
                            projects.readFile(projectId, path)
                        )
                }

                "andro_build_project" -> {

                    val projectId = args.getString("projectId")

                    val commitMessage = args.optString(
                        "commitMessage",
                        "Build Android project with Andro"
                    ).ifBlank {
                        "Build Android project with Andro"
                    }

                    // First push the project and submit the workflow dispatch.
                    val dispatch = githubBuilds.buildProject(
                        projectId = projectId,
                        commitMessage = commitMessage
                    )

                    // GitHub's dispatch endpoint does not return a run ID.
                    // Locate the run created by this dispatch.
                    var locatedRun: GitHubWorkflowRun? = null
                    var runLookupError: String? = null

                    try {
                        locatedRun = githubBuilds.findDispatchedRun(
                            projectId = projectId,
                            dispatch = dispatch
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Dispatch has already succeeded. A lookup failure
                        // must not be reported as a failed dispatch.
                        runLookupError =
                            e.message ?: "The workflow run is not visible yet."
                    }

                    JSONObject()
                        .put("projectId", projectId)
                        .put("repository", dispatch.repository)
                        .put("branch", dispatch.branch)
                        .put("workflowId", dispatch.workflowId)
                        .put("dispatchedAt", dispatch.dispatchedAt)
                        .put("executionEngine", "github-actions")
                        .put("dispatched", true)
                        .put(
                            "runId",
                            locatedRun?.id ?: JSONObject.NULL
                        )
                        .put(
                            "runStatus",
                            locatedRun?.status ?: JSONObject.NULL
                        )
                        .put(
                            "runUrl",
                            locatedRun?.htmlUrl ?: JSONObject.NULL
                        )
                        .put(
                            "runLookupComplete",
                            locatedRun != null
                        )
                        .put(
                            "runLookupError",
                            runLookupError ?: JSONObject.NULL
                        )
                        .put(
                            "message",
                            if (locatedRun != null) {
                                "Project synchronization completed, the workflow was dispatched, and its GitHub Actions run was located."
                            } else {
                                "Project synchronization and workflow dispatch succeeded. GitHub has not made the workflow run visible yet."
                            }
                        )
                        .put(
                            "nextStep",
                            if (locatedRun != null) {
                                "Call andro_get_remote_build_status with this projectId and runId to monitor the build."
                            } else {
                                "Check the repository's GitHub Actions page for the new run. Do not dispatch another build merely because run lookup is delayed."
                            }
                        )
                }

                "andro_get_remote_build_status" -> {

                    val projectId = args.getString("projectId")
                    val runId = args.getLong("runId")

                    if (runId <= 0) {
                        throw IllegalArgumentException(
                            "A valid GitHub Actions run ID is required."
                        )
                    }

                    val status = githubBuilds.getRemoteBuildStatus(
                        projectId = projectId,
                        runId = runId
                    )

                    val run = status.run

                    val artifacts = JSONArray().also { array ->
                        status.artifacts.forEach { artifact ->
                            array.put(
                                JSONObject()
                                    .put("id", artifact.id)
                                    .put("name", artifact.name)
                                    .put("sizeBytes", artifact.sizeBytes)
                                    .put("expired", artifact.expired)
                            )
                        }
                    }

                    JSONObject()
                        .put("projectId", projectId)
                        .put("runId", run.id)
                        .put("name", run.name)
                        .put("workflowId", run.workflowId)
                        .put("status", run.status)
                        .put(
                            "conclusion",
                            run.conclusion ?: JSONObject.NULL
                        )
                        .put("branch", run.branch)
                        .put("commitSha", run.commitSha)
                        .put("htmlUrl", run.htmlUrl)
                        .put("createdAt", run.createdAt)
                        .put("updatedAt", run.updatedAt)
                        .put("completed", status.completed)
                        .put("successful", status.successful)
                        .put("artifacts", artifacts)
                        .put(
                            "message",
                            when {
                                !status.completed ->
                                    "The GitHub Actions build is still running or queued."

                                status.successful ->
                                    "The GitHub Actions build completed successfully."

                                else ->
                                    "The GitHub Actions build finished without success."
                            }
                        )
                }

                "andro_get_build_status" -> {

                    val build = builds.get(
                        args.getString("buildId")
                    ) ?: throw IllegalArgumentException(
                        "Build request not found."
                    )

                    JSONObject()
                        .put("id", build.id)
                        .put("projectId", build.projectId)
                        .put("status", build.status)
                        .put("task", build.task)
                        .put("executionEngine", "github-actions")
                        .put("dispatched", false)
                        .put(
                            "exitCode",
                            build.exitCode ?: JSONObject.NULL
                        )
                        .put(
                            "logs",
                            JSONArray(build.logs)
                        )
                        .put(
                            "artifacts",
                            JSONArray(build.artifactPaths)
                        )
                        .put(
                            "message",
                            "This is a legacy local request record, not live GitHub Actions status."
                        )
                }

                else -> throw IllegalArgumentException(
                    "Unknown tool: $name"
                )
            }

            result(
                id,
                JSONObject().put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", data.toString())
                    )
                )
            )

        } catch (e: Exception) {

            result(
                id,
                JSONObject()
                    .put("isError", true)
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put(
                                    "text",
                                    e.message ?: "Tool failed"
                                )
                        )
                    )
            )
        }
    }

    private fun result(
        id: Any?,
        value: JSONObject
    ) =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", value)

    private fun error(
        id: Any?,
        code: Int,
        message: String
    ) =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
}

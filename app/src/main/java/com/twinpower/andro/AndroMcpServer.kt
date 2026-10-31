
package com.twinpower.andro

import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import org.json.JSONArray
import org.json.JSONObject

class AndroMcpServer(
    private val projects: ProjectRepository,
    private val builds: BuildManager
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
                                        .put("version", "0.3.0")
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
                "Create a remote Android build request. Local Gradle execution is disabled. GitHub Actions dispatch must be configured through Andro's remote build manager.",
                objectSchema("projectId", "task")
            )
        )

        list.put(
            tool(
                "andro_get_build_status",
                "Get the local remote-build request record. This does not query live GitHub Actions run status.",
                objectSchema("buildId")
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

                    val project = projects.getProject(
                        args.getString("projectId")
                    )

                    val task = args.optString(
                        "task",
                        "assembleDebug"
                    ).ifBlank {
                        "assembleDebug"
                    }

                    val snapshot = builds.start(
                        project,
                        task
                    )

                    JSONObject()
                        .put("buildId", snapshot.id)
                        .put("projectId", snapshot.projectId)
                        .put("status", snapshot.status)
                        .put("task", snapshot.task)
                        .put("executionEngine", "github-actions")
                        .put("dispatched", false)
                        .put(
                            "message",
                            "The request was recorded. No GitHub Actions workflow has been dispatched by this MCP tool."
                        )
                        .put(
                            "nextStep",
                            "Configure the GitHub repository and workflow in Andro's Remote Build Manager."
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
                            "This is the local request record, not live GitHub Actions status."
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

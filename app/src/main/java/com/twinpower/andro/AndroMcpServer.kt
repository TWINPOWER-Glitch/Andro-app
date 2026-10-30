package com.twinpower.andro

import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroMcpServer(
    private val projects: ProjectRepository,
    private val builds: BuildManager
) {
    @Volatile private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, host = "127.0.0.1", port = 8765) {
            routing {
                post("/mcp") {
                    val request = runCatching { JSONObject(call.receiveText()) }.getOrElse {
                        call.respondText(error(null, -32700, "Invalid JSON").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val method = request.optString("method")
                    val id = request.opt("id")
                    val params = request.optJSONObject("params") ?: JSONObject()
                    if (method.startsWith("notifications/")) {
                        call.respond(HttpStatusCode.Accepted)
                        return@post
                    }
                    val response = when (method) {
                        "initialize" -> result(id, JSONObject()
                            .put("protocolVersion", "2025-03-26")
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "andro-local").put("version", "0.2.0")))
                        "ping" -> result(id, JSONObject())
                        "tools/list" -> result(id, JSONObject().put("tools", tools()))
                        "tools/call" -> callTool(id, params)
                        else -> error(id, -32601, "Method not found: $method")
                    }
                    call.respondText(response.toString(), ContentType.Application.Json)
                }
                get("/health") {
                    call.respondText(
                        JSONObject().put("service", "andro-local-mcp").put("status", "ok").toString(),
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
        list.put(tool("andro_list_projects", "List projects imported into the local Andro workspace.", JSONObject().put("type", "object").put("properties", JSONObject())))
        list.put(tool("andro_get_project", "Get metadata for a local project.", objectSchema("projectId")))
        list.put(tool("andro_list_project_files", "List files in a local project.", objectSchema("projectId")))
        list.put(tool("andro_read_project_file", "Read a UTF-8 project file (maximum 512 KB).", objectSchema("projectId", "path")))
        list.put(tool("andro_build_project", "Run a Gradle task locally on this Android device.", objectSchema("projectId", "task")))
        list.put(tool("andro_get_build_status", "Get local Gradle build status, logs and artifact paths.", objectSchema("buildId")))
        return list
    }

    private fun tool(name: String, description: String, schema: JSONObject) =
        JSONObject().put("name", name).put("description", description).put("inputSchema", schema)

    private fun objectSchema(vararg required: String): JSONObject {
        val props = JSONObject()
        required.forEach { props.put(it, JSONObject().put("type", "string")) }
        return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
    }

    private suspend fun callTool(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val data: Any = when (name) {
                "andro_list_projects" -> JSONArray().also { array ->
                    projects.listProjects().forEach { p ->
                        array.put(JSONObject().put("id", p.id).put("name", p.name).put("hasGradleWrapper", p.hasGradleWrapper))
                    }
                }
                "andro_get_project" -> {
                    val p = projects.getProject(args.getString("projectId"))
                    JSONObject().put("id", p.id).put("name", p.name).put("rootPath", p.rootPath).put("hasGradleWrapper", p.hasGradleWrapper)
                }
                "andro_list_project_files" -> JSONArray(projects.listFiles(args.getString("projectId")))
                "andro_read_project_file" -> JSONObject().put("path", args.getString("path")).put("content", projects.readFile(args.getString("projectId"), args.getString("path")))
                "andro_build_project" -> {
                    val p = projects.getProject(args.getString("projectId"))
                    val snapshot = builds.start(p, args.optString("task", "assembleDebug"))
                    JSONObject().put("buildId", snapshot.id).put("status", snapshot.status).put("task", snapshot.task)
                }
                "andro_get_build_status" -> {
                    val b = builds.get(args.getString("buildId")) ?: error("Build not found")
                    JSONObject().put("id", b.id).put("status", b.status).put("task", b.task)
                        .put("exitCode", b.exitCode ?: JSONObject.NULL)
                        .put("logs", JSONArray(b.logs)).put("artifacts", JSONArray(b.artifactPaths))
                }
                else -> error("Unknown tool: $name")
            }
            result(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString()))))
        } catch (e: Exception) {
            result(id, JSONObject()
                .put("isError", true)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", e.message ?: "Tool failed"))))
        }
    }

    private fun result(id: Any?, value: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", value)

    private fun error(id: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
}


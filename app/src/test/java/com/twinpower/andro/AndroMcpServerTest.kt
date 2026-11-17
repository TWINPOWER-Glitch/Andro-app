package com.twinpower.andro

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroMcpServerTest {

    private lateinit var context: Context
    private lateinit var server: AndroMcpServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://127.0.0.1:8765"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        val repository = ProjectRepository(context)
        val builds = BuildManager(context, repository)

        val githubBuilds = GitHubMcpBuildService(
            context,
            repository
        )

        server = AndroMcpServer(
            repository,
            builds,
            githubBuilds
        )
    }

    private fun waitForServer(): Boolean {
        repeat(30) {
            try {
                client.newCall(
                    Request.Builder()
                        .url("$baseUrl/health")
                        .get()
                        .build()
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        return true
                    }
                }
            } catch (_: Exception) {
                // The CIO server may still be starting.
            }

            Thread.sleep(100)
        }

        return false
    }

    private fun postMcp(
        body: String,
        authorization: String? = null
    ): okhttp3.Response {
        val requestBuilder = Request.Builder()
            .url("$baseUrl/mcp")
            .post(
                body.toRequestBody(
                    "application/json".toMediaType()
                )
            )

        if (authorization != null) {
            requestBuilder.header(
                "Authorization",
                authorization
            )
        }

        return client.newCall(
            requestBuilder.build()
        ).execute()
    }

    @Test
    fun mcpRejectsUnauthorizedRequestsAndAcceptsAuthorizedRequests() {
        server.start()

        try {
            assertTrue(
                "Local MCP server did not become available.",
                waitForServer()
            )

            val requestBody = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "ping"
                }
            """.trimIndent()

            // No bearer token must result in HTTP 401.
            postMcp(requestBody).use { response ->
                assertEquals(401, response.code)
            }

            // A valid local bearer token must allow the request.
            val token = server.localAuthorizationToken()

            postMcp(
                requestBody,
                "Bearer $token"
            ).use { response ->
                assertEquals(200, response.code)

                val responseBody = JSONObject(
                    response.body!!.string()
                )

                assertEquals(
                    1,
                    responseBody.getInt("id")
                )

                assertTrue(
                    responseBody.has("result")
                )
            }

            // Authenticated tools/list requests must return the
            // available MCP tools.
            val toolsRequest = """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list"
                }
            """.trimIndent()

            postMcp(
                toolsRequest,
                "Bearer $token"
            ).use { response ->
                assertEquals(200, response.code)

                val responseBody = JSONObject(
                    response.body!!.string()
                )

                val tools = responseBody
                    .getJSONObject("result")
                    .getJSONArray("tools")

                assertTrue(tools.length() > 0)
            }
        } finally {
            server.stop()
        }
    }
}

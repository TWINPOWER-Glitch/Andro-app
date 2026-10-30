
package com.twinpower.andro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val RELAY_BASE =
    "https://andro-mcp-relay.kelvin-kiptoo-ke.workers.dev"

private data class RelayCredentials(
    val deviceId: String,
    val deviceToken: String
)

private object CloudflareRelaySecureStore {
    private const val PREFS = "andro_cloudflare_secure"
    private const val CREDENTIALS = "encrypted_device_credentials"
    private const val KEY_ALIAS = "andro_cloudflare_relay_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        return Base64.encodeToString(
            cipher.iv + encrypted,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12)

        val iv = payload.copyOfRange(0, 12)
        val encrypted = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }

    fun save(context: Context, credentials: RelayCredentials) {
        val value = JSONObject()
            .put("deviceId", credentials.deviceId)
            .put("deviceToken", credentials.deviceToken)
            .toString()

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(CREDENTIALS, encrypt(value))
            .apply()
    }

    fun read(context: Context): RelayCredentials? {
        val encrypted = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(CREDENTIALS, null) ?: return null

        return runCatching {
            val json = JSONObject(decrypt(encrypted))

            RelayCredentials(
                deviceId = json.getString("deviceId"),
                deviceToken = json.getString("deviceToken")
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(CREDENTIALS)
            .apply()
    }
}

class CloudflareMcpRelay(
    context: Context,
    private val localMcp: AndroMcpServer
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ioScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var statusListener: ((String, Boolean) -> Unit)? = null

    val isPaired: Boolean
        get() = CloudflareRelaySecureStore.read(appContext) != null

    val deviceId: String?
        get() = CloudflareRelaySecureStore.read(appContext)?.deviceId

    fun mcpEndpoint(): String? {
        val id = deviceId ?: return null
        return "$RELAY_BASE/v1/mcp/$id"
    }

    suspend fun pair(
        pairingCode: String,
        deviceName: String = "Andro Android device"
    ): String {
        require(pairingCode.isNotBlank()) {
            "Enter the one-time pairing code."
        }

        return withContext(Dispatchers.IO) {
            val requestBody = JSONObject()
                .put("pairingCode", pairingCode.trim())
                .put("deviceName", deviceName)
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("$RELAY_BASE/v1/device/pair")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val reason = runCatching {
                        JSONObject(raw).optString("error")
                    }.getOrNull()

                    throw IllegalStateException(
                        reason?.takeIf { it.isNotBlank() }
                            ?: "Pairing failed (${response.code})."
                    )
                }

                val json = JSONObject(raw)

                val credentials = RelayCredentials(
                    deviceId = json.getString("deviceId"),
                    deviceToken = json.getString("deviceToken")
                )

                CloudflareRelaySecureStore.save(
                    appContext,
                    credentials
                )

                credentials.deviceId
            }
        }
    }

    fun connect(onStatus: (String, Boolean) -> Unit) {
        val credentials =
            CloudflareRelaySecureStore.read(appContext)
                ?: throw IllegalStateException(
                    "Pair this Andro device before connecting."
                )

        statusListener = onStatus

        // The local MCP endpoint remains loopback-only.
        localMcp.start()

        val request = Request.Builder()
            .url(
                "$RELAY_BASE/v1/device/connect"
                    .replaceFirst("https://", "wss://")
            )
            .header(
                "Authorization",
                "Bearer ${credentials.deviceToken}"
            )
            .build()

        socket?.close(1000, "Reconnecting")

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    socket = webSocket
                    notifyStatus(
                        "Connected to Cloudflare relay.",
                        true
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    ioScope.launch {
                        processRelayMessage(webSocket, text)
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    if (socket === webSocket) socket = null

                    notifyStatus(
                        "Relay disconnected: $reason",
                        false
                    )
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    if (socket === webSocket) socket = null

                    notifyStatus(
                        "Relay connection failed: ${
                            t.message ?: "network error"
                        }",
                        false
                    )
                }
            }
        )
    }

    fun disconnect() {
        socket?.close(1000, "Disconnected by user")
        socket = null
        notifyStatus("Disconnected.", false)
    }

    suspend fun createClientToken(
        name: String,
        allowBuild: Boolean
    ): JSONObject {
        val credentials =
            CloudflareRelaySecureStore.read(appContext)
                ?: throw IllegalStateException(
                    "This device is not paired."
                )

        return withContext(Dispatchers.IO) {
            val scopes = JSONArray().put("mcp:read")

            if (allowBuild) {
                scopes.put("mcp:build")
            }

            val requestBody = JSONObject()
                .put(
                    "name",
                    name.ifBlank { "MCP client" }
                )
                .put("scopes", scopes)
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("$RELAY_BASE/v1/device/client-token")
                .header(
                    "Authorization",
                    "Bearer ${credentials.deviceToken}"
                )
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val reason = runCatching {
                        JSONObject(raw).optString("error")
                    }.getOrNull()

                    throw IllegalStateException(
                        reason?.takeIf { it.isNotBlank() }
                            ?: "Could not create MCP token (${response.code})."
                    )
                }

                JSONObject(raw)
            }
        }
    }

    suspend fun createPairingCode(
        deviceName: String
    ): JSONObject {
        val credentials =
            CloudflareRelaySecureStore.read(appContext)
                ?: throw IllegalStateException(
                    "This device is not paired."
                )

        return withContext(Dispatchers.IO) {
            val requestBody = JSONObject()
                .put("deviceName", deviceName)
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("$RELAY_BASE/v1/device/pairing")
                .header(
                    "Authorization",
                    "Bearer ${credentials.deviceToken}"
                )
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        JSONObject(raw).optString(
                            "error",
                            "Could not create pairing code."
                        )
                    )
                }

                JSONObject(raw)
            }
        }
    }

    suspend fun revokeClientToken(tokenId: String) {
        val credentials =
            CloudflareRelaySecureStore.read(appContext)
                ?: throw IllegalStateException(
                    "This device is not paired."
                )

        withContext(Dispatchers.IO) {
            val requestBody = JSONObject()
                .put("tokenId", tokenId)
                .toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("$RELAY_BASE/v1/device/revoke-client")
                .header(
                    "Authorization",
                    "Bearer ${credentials.deviceToken}"
                )
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Client-token revocation failed (${response.code})."
                    )
                }
            }
        }
    }

    suspend fun revokeDevice() {
        val credentials =
            CloudflareRelaySecureStore.read(appContext)
                ?: return

        withContext(Dispatchers.IO) {
            val requestBody = "{}".toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

            val request = Request.Builder()
                .url("$RELAY_BASE/v1/device/revoke-self")
                .header(
                    "Authorization",
                    "Bearer ${credentials.deviceToken}"
                )
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Device revocation failed (${response.code})."
                    )
                }
            }
        }

        disconnect()
        CloudflareRelaySecureStore.clear(appContext)
    }

    private suspend fun processRelayMessage(
        webSocket: WebSocket,
        text: String
    ) {
        val envelope = runCatching {
            JSONObject(text)
        }.getOrNull() ?: return

        if (envelope.optString("type") != "mcp_request") {
            return
        }

        val requestId = envelope.optString("requestId")
        val mcpRequest =
            envelope.optJSONObject("body") ?: JSONObject()

        val responseBody = runCatching {
            val localBody = mcpRequest.toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("http://127.0.0.1:8765/mcp")
                .post(localBody)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (raw.isBlank()) {
                    JSONObject()
                } else {
                    JSONObject(raw)
                }
            }
        }.getOrElse { error ->
            JSONObject()
                .put("jsonrpc", "2.0")
                .put(
                    "id",
                    mcpRequest.opt("id") ?: JSONObject.NULL
                )
                .put(
                    "error",
                    JSONObject()
                        .put("code", -32603)
                        .put(
                            "message",
                            error.message
                                ?: "Local MCP request failed"
                        )
                )
        }

        webSocket.send(
            JSONObject()
                .put("type", "mcp_response")
                .put("requestId", requestId)
                .put("body", responseBody)
                .toString()
        )
    }

    private fun notifyStatus(
        message: String,
        connected: Boolean
    ) {
        mainHandler.post {
            statusListener?.invoke(message, connected)
        }
    }
}

@Composable
fun CloudflareMcpConnections(
    mcp: AndroMcpServer,
    onLocalServerStarted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val relay = remember {
        CloudflareMcpRelay(context, mcp)
    }

    var pairingCode by remember {
        mutableStateOf("")
    }

    var paired by remember {
        mutableStateOf(relay.isPaired)
    }

    var deviceId by remember {
        mutableStateOf(relay.deviceId.orEmpty())
    }

    var status by remember {
        mutableStateOf(
            if (relay.isPaired) {
                "Paired · not connected"
            } else {
                "Not paired"
            }
        )
    }

    var connected by remember {
        mutableStateOf(false)
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var clientName by remember {
        mutableStateOf("MCP client")
    }

    var allowBuild by remember {
        mutableStateOf(false)
    }

    var clientToken by remember {
        mutableStateOf("")
    }

    var tokenId by remember {
        mutableStateOf("")
    }

    var tokenScopes by remember {
        mutableStateOf("")
    }

    var endpoint by remember {
        mutableStateOf(relay.mcpEndpoint().orEmpty())
    }

    var newPairingCode by remember {
        mutableStateOf("")
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "SECURE CLOUDFLARE MCP RELAY",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                "Pair this phone once. Andro maintains an outbound encrypted connection to Cloudflare. MCP clients use separate scoped access tokens."
            )

            if (!paired) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("One-time pairing code")
                    },
                    singleLine = true
                )

                Button(
                    onClick = {
                        scope.launch {
                            busy = true

                            runCatching {
                                relay.pair(pairingCode)
                            }.onSuccess { id ->
                                deviceId = id
                                endpoint =
                                    relay.mcpEndpoint().orEmpty()
                                paired = true
                                pairingCode = ""
                                status = "Paired · not connected"
                            }.onFailure {
                                status =
                                    "Pairing failed: ${it.message}"
                            }

                            busy = false
                        }
                    },
                    enabled = pairingCode.isNotBlank() && !busy
                ) {
                    Text(
                        if (busy) "Pairing…" else "Pair this device"
                    )
                }
            } else {
                Text(
                    "Device ID: $deviceId",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    status,
                    color = if (connected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                mcp.start()
                                onLocalServerStarted()

                                relay.connect { message, online ->
                                    status = message
                                    connected = online
                                }

                                status = "Connecting…"
                            }.onFailure {
                                status =
                                    "Connection error: ${it.message}"
                            }
                        },
                        enabled = !connected && !busy
                    ) {
                        Text("Connect")
                    }

                    OutlinedButton(
                        onClick = {
                            relay.disconnect()
                            connected = false
                        },
                        enabled = connected
                    ) {
                        Text("Disconnect")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true

                                runCatching {
                                    relay.revokeDevice()
                                }.onSuccess {
                                    paired = false
                                    connected = false
                                    deviceId = ""
                                    endpoint = ""
                                    clientToken = ""
                                    tokenId = ""
                                    newPairingCode = ""
                                    status = "Device revoked and forgotten."
                                }.onFailure {
                                    status =
                                        "Revocation failed: ${it.message}"
                                }

                                busy = false
                            }
                        },
                        enabled = !busy
                    ) {
                        Text("Revoke device")
                    }
                }

                HorizontalDivider()

                Text(
                    "PAIR ANOTHER DEVICE",
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        scope.launch {
                            busy = true

                            runCatching {
                                relay.createPairingCode(
                                    "Another Andro device"
                                )
                            }.onSuccess {
                                newPairingCode =
                                    it.getString("pairingCode")
                            }.onFailure {
                                status =
                                    "Could not create pairing code: ${it.message}"
                            }

                            busy = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text("Generate pairing code")
                }

                if (newPairingCode.isNotBlank()) {
                    Text(
                        "One-time code · expires in 10 minutes",
                        color = MaterialTheme.colorScheme.error
                    )

                    SelectionContainer {
                        Text(
                            newPairingCode,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager

                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Andro pairing code",
                                    newPairingCode
                                )
                            )

                            status = "Pairing code copied."
                        }
                    ) {
                        Text("Copy pairing code")
                    }
                }

                HorizontalDivider()

                Text(
                    "MCP CLIENT ACCESS",
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Client name") },
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allowBuild,
                        onCheckedChange = { allowBuild = it }
                    )

                    Spacer(Modifier.width(4.dp))

                    Text("Allow Gradle builds")
                }

                Text(
                    if (allowBuild) {
                        "This token can read workspace data and start local Gradle builds."
                    } else {
                        "Read-only token: workspace and build-status tools only."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = {
                        scope.launch {
                            busy = true

                            runCatching {
                                relay.createClientToken(
                                    clientName,
                                    allowBuild
                                )
                            }.onSuccess { result ->
                                clientToken =
                                    result.getString("clientToken")

                                tokenId =
                                    result.getString("tokenId")

                                tokenScopes =
                                    result.getJSONArray("scopes").toString()

                                endpoint =
                                    relay.mcpEndpoint().orEmpty()
                            }.onFailure {
                                status =
                                    "Token creation failed: ${it.message}"
                            }

                            busy = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text(
                        if (busy) "Creating…" else "Create MCP access token"
                    )
                }

                if (clientToken.isNotBlank()) {
                    Text(
                        "Copy this token now. It is shown only once.",
                        color = MaterialTheme.colorScheme.error
                    )

                    SelectionContainer {
                        Text(
                            clientToken,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager

                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Andro MCP token",
                                    clientToken
                                )
                            )

                            status = "MCP token copied."
                        }
                    ) {
                        Text("Copy access token")
                    }

                    Text(
                        "Token ID: $tokenId",
                        style = MaterialTheme.typography.labelSmall
                    )

                    Text(
                        "Scopes: $tokenScopes",
                        style = MaterialTheme.typography.labelSmall
                    )

                    Text(
                        "MCP endpoint",
                        fontWeight = FontWeight.Bold
                    )

                    SelectionContainer {
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        "Configure the MCP client to POST JSON-RPC to this endpoint and send the HTTP header Authorization: Bearer <access-token>.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true

                                runCatching {
                                    relay.revokeClientToken(tokenId)
                                }.onSuccess {
                                    clientToken = ""
                                    tokenId = ""
                                    tokenScopes = ""
                                    status = "Client token revoked."
                                }.onFailure {
                                    status =
                                        "Token revocation failed: ${it.message}"
                                }

                                busy = false
                            }
                        },
                        enabled = !busy
                    ) {
                        Text("Revoke this client token")
                    }
                }

                Text(
                    "Device credentials are encrypted with Android Keystore. Device tokens expire after 90 days; client tokens expire after 30 days. Keep Andro running while you want the remote connection active.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

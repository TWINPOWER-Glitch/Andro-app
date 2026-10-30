
package com.twinpower.andro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val GITHUB_APP_CLIENT_ID = "Iv23lijiUxIqqd01HH50"

data class GitHubRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val private: Boolean,
    val defaultBranch: String,
    val htmlUrl: String
)

private data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

private object GitHubTokenStore {
    private const val PREFS = "andro_github_secure"
    private const val TOKEN = "encrypted_token"
    private const val ALIAS = "andro_github_token_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
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

    fun save(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())

        val encrypted = cipher.doFinal(
            token.toByteArray(Charsets.UTF_8)
        )

        val payload = cipher.iv + encrypted
        val encoded = Base64.encodeToString(
            payload,
            Base64.NO_WRAP
        )

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(TOKEN, encoded)
            .apply()
    }

    fun read(context: Context): String? {
        val encoded = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(TOKEN, null) ?: return null

        return runCatching {
            val payload = Base64.decode(
                encoded,
                Base64.NO_WRAP
            )

            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(
                12,
                payload.size
            )

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(TOKEN)
            .apply()
    }
}

private object GitHubApi {
    private const val API = "https://api.github.com"

    private fun form(
        vararg values: Pair<String, String>
    ): String {
        return values.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") +
                "=" +
                URLEncoder.encode(it.second, "UTF-8")
        }
    }

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
        accept: String = "application/vnd.github+json"
    ): String {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty(
                "X-GitHub-Api-Version",
                "2022-11-28"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Andro-Android-App"
            )

            if (token != null) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
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
                ?: ""

            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optString("message")
                }.getOrDefault(response)

                throw IllegalStateException(
                    "GitHub returned HTTP $status: $detail"
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requestDeviceCode(): GitHubDeviceCode =
        withContext(Dispatchers.IO) {
            val response = JSONObject(
                request(
                    url = "https://github.com/login/device/code",
                    method = "POST",
                    body = form(
                        "client_id" to GITHUB_APP_CLIENT_ID
                    ),
                    accept = "application/json"
                )
            )

            if (response.has("error")) {
                throw IllegalStateException(
                    response.optString(
                        "error_description",
                        response.optString("error")
                    )
                )
            }

            GitHubDeviceCode(
                deviceCode = response.getString("device_code"),
                userCode = response.getString("user_code"),
                verificationUri = response.optString(
                    "verification_uri",
                    "https://github.com/login/device"
                ),
                expiresIn = response.optInt("expires_in", 900),
                interval = response.optInt("interval", 5)
            )
        }

    suspend fun pollForToken(
        device: GitHubDeviceCode
    ): String = withContext(Dispatchers.IO) {

        val deadline =
            System.currentTimeMillis() +
                device.expiresIn * 1000L

        var interval = device.interval.coerceAtLeast(5)

        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)

            val response = JSONObject(
                request(
                    url = "https://github.com/login/oauth/access_token",
                    method = "POST",
                    body = form(
                        "client_id" to GITHUB_APP_CLIENT_ID,
                        "device_code" to device.deviceCode,
                        "grant_type" to
                            "urn:ietf:params:oauth:grant-type:device_code"
                    ),
                    accept = "application/json"
                )
            )

            val token = response.optString("access_token")

            if (token.isNotBlank()) {
                return@withContext token
            }

            when (response.optString("error")) {
                "authorization_pending" -> Unit

                "slow_down" -> interval += 5

                "access_denied" -> throw IllegalStateException(
                    "GitHub authorization was denied."
                )

                "expired_token" -> throw IllegalStateException(
                    "The GitHub device code expired. Start again."
                )

                else -> throw IllegalStateException(
                    response.optString(
                        "error_description",
                        response.optString(
                            "error",
                            "GitHub authorization failed."
                        )
                    )
                )
            }
        }

        throw IllegalStateException(
            "The GitHub device code expired. Start again."
        )
    }

    suspend fun repositories(
        token: String
    ): List<GitHubRepository> =
        withContext(Dispatchers.IO) {

            val response = JSONArray(
                request(
                    url = API +
                        "/user/repos?per_page=100&sort=updated" +
                        "&affiliation=owner,collaborator,organization_member",
                    token = token
                )
            )

            buildList {
                for (index in 0 until response.length()) {
                    val repo = response.getJSONObject(index)

                    add(
                        GitHubRepository(
                            id = repo.optLong("id"),
                            name = repo.optString("name"),
                            fullName = repo.optString("full_name"),
                            private = repo.optBoolean("private"),
                            defaultBranch = repo.optString(
                                "default_branch",
                                "main"
                            ),
                            htmlUrl = repo.optString("html_url")
                        )
                    )
                }
            }
        }
}

@Composable
fun GitHubConnections() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember {
        mutableStateOf(GitHubTokenStore.read(context))
    }

    var repositories by remember {
        mutableStateOf<List<GitHubRepository>>(emptyList())
    }

    var deviceCode by remember {
        mutableStateOf<GitHubDeviceCode?>(null)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf(
            "Connect your GitHub account to browse repositories."
        )
    }

    LaunchedEffect(token) {
        val currentToken = token ?: return@LaunchedEffect

        loading = true

        runCatching {
            GitHubApi.repositories(currentToken)
        }.onSuccess {
            repositories = it
            status = "Connected. ${it.size} repositories available."
        }.onFailure {
            repositories = emptyList()
            status = "GitHub connection error: ${it.message}"

            if (it.message?.contains("HTTP 401") == true) {
                GitHubTokenStore.clear(context)
                token = null
            }
        }

        loading = false
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "GITHUB",
                fontWeight = FontWeight.Bold
            )

            Text(
                status,
                style = MaterialTheme.typography.bodySmall
            )

            if (loading) {
                CircularProgressIndicator()
            }

            if (token == null && deviceCode == null) {
                Button(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true

                            runCatching {
                                GitHubApi.requestDeviceCode()
                            }.onSuccess { code ->
                                deviceCode = code
                                status =
                                    "Enter the one-time code on GitHub."

                                scope.launch {
                                    runCatching {
                                        GitHubApi.pollForToken(code)
                                    }.onSuccess { newToken ->
                                        GitHubTokenStore.save(
                                            context,
                                            newToken
                                        )

                                        token = newToken
                                        deviceCode = null

                                        status =
                                            "GitHub connected. Loading repositories..."
                                    }.onFailure {
                                        status =
                                            it.message
                                                ?: "GitHub authorization failed."

                                        deviceCode = null
                                    }

                                    loading = false
                                }
                            }.onFailure {
                                status =
                                    it.message
                                        ?: "Could not start GitHub authorization."

                                loading = false
                            }
                        }
                    }
                ) {
                    Text("Connect GitHub")
                }
            }

            deviceCode?.let { code ->
                Text(
                    "ONE-TIME VERIFICATION CODE",
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    code.userCode,
                    style = MaterialTheme.typography.headlineMedium
                )

                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(code.verificationUri)
                            )
                        )
                    }
                ) {
                    Text("Open GitHub device page")
                }

                Text(
                    "Keep Andro running while you authorize this device.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (token != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            scope.launch {
                                loading = true

                                runCatching {
                                    GitHubApi.repositories(token!!)
                                }.onSuccess {
                                    repositories = it
                                    status = "Repository list refreshed."
                                }.onFailure {
                                    status =
                                        it.message ?: "Refresh failed."
                                }

                                loading = false
                            }
                        }
                    ) {
                        Text("Refresh repos")
                    }

                    OutlinedButton(
                        onClick = {
                            GitHubTokenStore.clear(context)
                            token = null
                            repositories = emptyList()
                            status = "GitHub disconnected from this device."
                        }
                    ) {
                        Text("Disconnect")
                    }
                }
            }

            if (repositories.isNotEmpty()) {
                Text(
                    "AUTHORIZED REPOSITORIES · ${repositories.size}",
                    fontWeight = FontWeight.SemiBold
                )

                repositories.forEach { repo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(repo.htmlUrl)
                                    )
                                )
                            }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            repo.fullName,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            (if (repo.private) "Private" else "Public") +
                                " · default branch: " +
                                repo.defaultBranch,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else if (token != null && !loading) {
                Text(
                    "No repositories were returned.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "The GitHub token is encrypted using Android Keystore. " +
                    "No GitHub client secret is embedded in this application.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

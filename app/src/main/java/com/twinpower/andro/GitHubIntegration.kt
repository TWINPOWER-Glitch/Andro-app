package com.twinpower.andro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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

internal data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
    val createdAtMillis: Long = System.currentTimeMillis()
)

private data class GitHubEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long
)

internal object GitHubSecureStore {
    private const val PREFS = "andro_github_secure"
    private const val TOKEN = "encrypted_token"
    private const val PENDING = "encrypted_pending_auth"
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

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(TOKEN, encrypt(token))
            .apply()
    }

    fun readToken(context: Context): String? {
        val value = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(TOKEN, null) ?: return null

        return runCatching {
            decrypt(value)
        }.getOrNull()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(TOKEN)
            .apply()
    }

    fun savePending(
        context: Context,
        device: GitHubDeviceCode
    ) {
        val json = JSONObject().apply {
            put("device_code", device.deviceCode)
            put("user_code", device.userCode)
            put("verification_uri", device.verificationUri)
            put("expires_in", device.expiresIn)
            put("interval", device.interval)
            put("created_at", device.createdAtMillis)
        }

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(PENDING, encrypt(json.toString()))
            .commit()
    }

    fun readPending(context: Context): GitHubDeviceCode? {
        val value = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(PENDING, null) ?: return null

        return runCatching {
            val json = JSONObject(decrypt(value))

            GitHubDeviceCode(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.getString("verification_uri"),
                expiresIn = json.getInt("expires_in"),
                interval = json.getInt("interval"),
                createdAtMillis = json.getLong("created_at")
            )
        }.getOrNull()
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(PENDING)
            .commit()
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
        contentType: String = "application/json",
        accept: String = "application/vnd.github+json"
    ): String {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000

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
                    contentType
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
                    "GitHub HTTP $status: $detail"
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(path: String): String {
        return path.split("/")
            .joinToString("/") {
                URLEncoder.encode(it, "UTF-8")
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
                    contentType = "application/x-www-form-urlencoded",
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
            device.createdAtMillis + device.expiresIn * 1000L

        var interval = device.interval.coerceAtLeast(5)

        while (System.currentTimeMillis() < deadline) {
            val remaining =
                deadline - System.currentTimeMillis()

            delay(minOf(interval * 1000L, remaining))

            if (System.currentTimeMillis() >= deadline) break

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
                    contentType = "application/x-www-form-urlencoded",
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
                    "The device code expired. Start again."
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
            "The device code expired. Start again."
        )
    }

    suspend fun repositories(
        token: String
    ): List<GitHubRepository> =
        withContext(Dispatchers.IO) {
            buildList {
                var page = 1

                while (page <= 10) {
                    val response = JSONArray(
                        request(
                            API +
                                "/user/repos?per_page=100&page=$page" +
                                "&sort=updated" +
                                "&affiliation=owner,collaborator,organization_member",
                            token = token
                        )
                    )

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

                    if (response.length() < 100) break
                    page++
                }
            }
        }

    suspend fun createRepository(
        token: String,
        name: String,
        description: String,
        private: Boolean
    ): GitHubRepository = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", private)
            put("auto_init", true)
            put("has_issues", true)
            put("has_projects", false)
            put("has_wiki", false)
        }

        val response = JSONObject(
            request(
                url = "$API/user/repos",
                method = "POST",
                token = token,
                body = body.toString()
            )
        )

        GitHubRepository(
            id = response.optLong("id"),
            name = response.optString("name"),
            fullName = response.optString("full_name"),
            private = response.optBoolean("private"),
            defaultBranch = response.optString(
                "default_branch",
                "main"
            ),
            htmlUrl = response.optString("html_url")
        )
    }

    suspend fun installBuildWorkflow(
        token: String,
        repo: GitHubRepository
    ) = withContext(Dispatchers.IO) {
        val workflow = """
name: Andro Android Build

on:
  push:
    branches:
      - main
      - master
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  android-build:
    name: Android tests and APK build
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.13"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          accept-android-sdk-licenses: true
          packages: platform-tools

      - name: Install Android SDK packages
        run: |
          sdkmanager "platform-tools" "platforms;android-35" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0"

      - name: Detect Android Gradle project
        id: detect
        shell: bash
        run: |
          if [[ -f settings.gradle || -f settings.gradle.kts ]] && [[ -f app/build.gradle || -f app/build.gradle.kts ]]; then
            echo "android_project=true" >> "${'$'}GITHUB_OUTPUT"
          else
            echo "android_project=false" >> "${'$'}GITHUB_OUTPUT"
          fi

      - name: Explain empty repository
        if: steps.detect.outputs.android_project != 'true'
        run: |
          echo "Andro build workflow is installed."
          echo "No Android Gradle project was found yet."
          echo "Add the Android project source files and push again to start an APK build."

      - name: Run tests, lint and build APKs
        if: steps.detect.outputs.android_project == 'true'
        shell: bash
        run: |
          set -euo pipefail

          if [[ -f ./gradlew ]]; then
            chmod +x ./gradlew
            ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
          else
            gradle --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
          fi

      - name: Upload debug APK
        if: steps.detect.outputs.android_project == 'true'
        uses: actions/upload-artifact@v4
        with:
          name: andro-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
          retention-days: 14

      - name: Upload release APK
        if: steps.detect.outputs.android_project == 'true'
        uses: actions/upload-artifact@v4
        with:
          name: andro-release-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
          retention-days: 14

      - name: Build summary
        if: always()
        run: |
          echo "Andro Android workflow finished."
        """.trimIndent()

        val encodedContent = Base64.encodeToString(
            workflow.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val body = JSONObject().apply {
            put(
                "message",
                "Add Andro Android build workflow"
            )
            put("content", encodedContent)
            put("branch", repo.defaultBranch)
        }

        request(
            url = "$API/repos/${repo.fullName}/contents/" +
                ".github/workflows/andro-android-build.yml",
            method = "PUT",
            token = token,
            body = body.toString()
        )
    }

    suspend fun branches(
        token: String,
        repo: GitHubRepository
    ): List<String> = withContext(Dispatchers.IO) {
        val response = JSONArray(
            request(
                "$API/repos/${repo.fullName}/branches?per_page=100",
                token = token
            )
        )

        buildList {
            for (index in 0 until response.length()) {
                add(
                    response.getJSONObject(index)
                        .optString("name")
                )
            }
        }
    }

    suspend fun contents(
        token: String,
        repo: GitHubRepository,
        path: String,
        branch: String
    ): List<GitHubEntry> = withContext(Dispatchers.IO) {
        val encodedPath = encodePath(path)
        val url = "$API/repos/${repo.fullName}/contents/$encodedPath" +
            "?ref=${URLEncoder.encode(branch, "UTF-8")}"

        val response = request(url, token = token)

        if (response.trimStart().startsWith("{")) {
            val file = JSONObject(response)
            throw IllegalStateException(
                "This path is a file, not a directory: " +
                    file.optString("name")
            )
        }

        val array = JSONArray(response)

        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)

                add(
                    GitHubEntry(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        type = item.optString("type"),
                        size = item.optLong("size")
                    )
                )
            }
        }.sortedWith(
            compareBy<GitHubEntry> {
                if (it.type == "dir") 0 else 1
            }.thenBy { it.name.lowercase() }
        )
    }

    suspend fun fileContent(
        token: String,
        repo: GitHubRepository,
        path: String,
        branch: String
    ): String = withContext(Dispatchers.IO) {
        val encodedPath = encodePath(path)
        val url = "$API/repos/${repo.fullName}/contents/$encodedPath" +
            "?ref=${URLEncoder.encode(branch, "UTF-8")}"

        val response = JSONObject(
            request(url, token = token)
        )

        require(response.optLong("size") <= 512 * 1024) {
            "File is larger than the 512 KB preview limit."
        }

        require(response.optString("type") == "file") {
            "Selected item is not a file."
        }

        val encoded = response.optString("content")
            .replace("\\n", "")
            .replace("\\r", "")
            .filterNot { it.isWhitespace() }

        require(encoded.isNotBlank()) {
            "GitHub returned no file content."
        }

        String(
            Base64.decode(encoded, Base64.DEFAULT),
            Charsets.UTF_8
        )
    }
}

@Composable
fun GitHubConnections() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember {
        mutableStateOf(GitHubSecureStore.readToken(context))
    }

    var pendingAuth by remember {
        mutableStateOf(GitHubSecureStore.readPending(context))
    }

    var repositories by remember {
        mutableStateOf<List<GitHubRepository>>(emptyList())
    }

    var selectedRepo by remember {
        mutableStateOf<GitHubRepository?>(null)
    }

    var branches by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var selectedBranch by remember {
        mutableStateOf("")
    }

    var branchMenu by remember {
        mutableStateOf(false)
    }

    var currentPath by remember {
        mutableStateOf("")
    }

    var entries by remember {
        mutableStateOf<List<GitHubEntry>>(emptyList())
    }

    var repositorySearch by remember {
        mutableStateOf("")
    }

    var entrySearch by remember {
        mutableStateOf("")
    }

    var previewTitle by remember {
        mutableStateOf<String?>(null)
    }

    var previewContent by remember {
        mutableStateOf("")
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("Connect GitHub to browse and manage repositories.")
    }

    var showCreateRepository by remember {
        mutableStateOf(false)
    }

    var newRepositoryName by remember {
        mutableStateOf("")
    }

    var newRepositoryDescription by remember {
        mutableStateOf("")
    }

    var newRepositoryPrivate by remember {
        mutableStateOf(true)
    }

    fun loadRepositories(activeToken: String) {
        scope.launch {
            loading = true

            runCatching {
                GitHubApi.repositories(activeToken)
            }.onSuccess {
                repositories = it
                status = "Connected · ${it.size} repositories loaded."
            }.onFailure {
                status = it.message ?: "Could not load repositories."

                if (it.message?.contains("HTTP 401") == true) {
                    GitHubSecureStore.clearToken(context)
                    token = null
                    repositories = emptyList()
                }
            }

            loading = false
        }
    }

    fun loadDirectory(
        activeToken: String,
        repo: GitHubRepository,
        path: String,
        branch: String
    ) {
        scope.launch {
            loading = true
            entries = emptyList()

            runCatching {
                GitHubApi.contents(
                    activeToken,
                    repo,
                    path,
                    branch
                )
            }.onSuccess {
                entries = it
                currentPath = path
                entrySearch = ""
                status = "${it.size} items · ${repo.fullName}"
            }.onFailure {
                status = it.message
                    ?: "Could not load repository contents."
            }

            loading = false
        }
    }

    LaunchedEffect(token) {
        val activeToken = token ?: return@LaunchedEffect
        loadRepositories(activeToken)
    }

    LaunchedEffect(
        pendingAuth?.deviceCode,
        token
    ) {
        val device = pendingAuth ?: return@LaunchedEffect
        if (token != null) return@LaunchedEffect

        loading = true
        status = "Waiting for GitHub authorization..."

        runCatching {
            GitHubApi.pollForToken(device)
        }.onSuccess { newToken ->
            GitHubSecureStore.saveToken(context, newToken)
            GitHubSecureStore.clearPending(context)

            pendingAuth = null
            token = newToken
            status = "GitHub connected successfully."
        }.onFailure {
            GitHubSecureStore.clearPending(context)
            pendingAuth = null
            status = it.message ?: "Authorization failed."
        }

        loading = false
    }

    LaunchedEffect(selectedRepo?.fullName, token) {
        val activeToken = token ?: return@LaunchedEffect
        val repo = selectedRepo ?: return@LaunchedEffect

        loading = true

        runCatching {
            GitHubApi.branches(activeToken, repo)
        }.onSuccess { result ->
            branches = result

            selectedBranch = result.firstOrNull {
                it == repo.defaultBranch
            } ?: result.firstOrNull() ?: repo.defaultBranch
        }.onFailure {
            branches = listOf(repo.defaultBranch)
            selectedBranch = repo.defaultBranch
            status = it.message ?: "Could not load branches."
        }

        loading = false
    }

    LaunchedEffect(
        selectedRepo?.fullName,
        selectedBranch,
        token
    ) {
        val activeToken = token ?: return@LaunchedEffect
        val repo = selectedRepo ?: return@LaunchedEffect

        if (selectedBranch.isNotBlank()) {
            loadDirectory(
                activeToken,
                repo,
                "",
                selectedBranch
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "GITHUB INTEGRATION",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall
                )

                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (token == null && pendingAuth == null) {
                    Button(
                        enabled = !loading,
                        onClick = {
                            scope.launch {
                                loading = true

                                runCatching {
                                    GitHubApi.requestDeviceCode()
                                }.onSuccess { code ->
                                    GitHubSecureStore.savePending(
                                        context,
                                        code
                                    )

                                    pendingAuth = code
                                    status = "Copy the code and authorize Andro on GitHub."
                                }.onFailure {
                                    status = it.message
                                        ?: "Could not start authorization."
                                }

                                loading = false
                            }
                        }
                    ) {
                        Text("Connect GitHub")
                    }
                }

                pendingAuth?.let { code ->
                    Text(
                        "YOUR GITHUB DEVICE CODE",
                        fontWeight = FontWeight.Bold
                    )

                    SelectionContainer {
                        Text(
                            code.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val clipboard =
                                    context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager

                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "GitHub device code",
                                        code.userCode
                                    )
                                )

                                Toast.makeText(
                                    context,
                                    "Device code copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Copy code")
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(code.verificationUri)
                                    )
                                )
                            }
                        ) {
                            Text("Open GitHub")
                        }
                    }

                    Text(
                        "Authorization continues while you use the browser. " +
                            "Andro saves this pending authorization securely " +
                            "so it can resume if Android recreates the app.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    TextButton(
                        onClick = {
                            GitHubSecureStore.clearPending(context)
                            pendingAuth = null
                            status = "GitHub authorization cancelled."
                        }
                    ) {
                        Text("Cancel authorization")
                    }
                }

                if (token != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                loadRepositories(token!!)
                            }
                        ) {
                            Text("Refresh")
                        }

                        OutlinedButton(
                            onClick = {
                                GitHubSecureStore.clearToken(context)
                                GitHubSecureStore.clearPending(context)

                                token = null
                                pendingAuth = null
                                repositories = emptyList()
                                selectedRepo = null
                                entries = emptyList()
                                status = "GitHub disconnected."
                            }
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        if (token != null && selectedRepo == null) {
            Card {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "REPOSITORY MANAGEMENT",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Browse existing repositories or create a new repository. " +
                            "New repositories receive an Andro Android build workflow."
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                        onClick = {
                            newRepositoryName = ""
                            newRepositoryDescription = ""
                            newRepositoryPrivate = true
                            showCreateRepository = true
                        }
                    ) {
                        Text("＋ Create new repository")
                    }

                    HorizontalDivider()

                    Text(
                        "REPOSITORY EXPLORER",
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = repositorySearch,
                        onValueChange = { repositorySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search repositories") },
                        singleLine = true
                    )

                    Text(
                        "${repositories.size} repositories available",
                        style = MaterialTheme.typography.labelMedium
                    )

                    val filtered = repositories.filter {
                        it.fullName.contains(
                            repositorySearch,
                            ignoreCase = true
                        )
                    }

                    filtered.forEach { repo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPath = ""
                                    entries = emptyList()
                                    branches = emptyList()
                                    selectedBranch = ""
                                    selectedRepo = repo
                                },
                            colors = CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    repo.fullName,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    (if (repo.private) "Private" else "Public") +
                                        " · ${repo.defaultBranch}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Text(
                                    "Browse repository →",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedRepo?.let { repo ->
            Card {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "REPOSITORY BROWSER",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                repo.fullName,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        TextButton(
                            onClick = {
                                selectedRepo = null
                                entries = emptyList()
                                currentPath = ""
                            }
                        ) {
                            Text("Repositories")
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { branchMenu = true }
                        ) {
                            Text("Branch: $selectedBranch")
                        }

                        DropdownMenu(
                            expanded = branchMenu,
                            onDismissRequest = { branchMenu = false }
                        ) {
                            branches.forEach { branch ->
                                DropdownMenuItem(
                                    text = { Text(branch) },
                                    onClick = {
                                        branchMenu = false
                                        selectedBranch = branch
                                    }
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                val activeToken = token
                                if (activeToken != null) {
                                    loadDirectory(
                                        activeToken,
                                        repo,
                                        currentPath,
                                        selectedBranch
                                    )
                                }
                            }
                        ) {
                            Text("Reload")
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            enabled = currentPath.isNotBlank(),
                            onClick = {
                                val parent =
                                    currentPath.substringBeforeLast("/", "")

                                val activeToken = token
                                if (activeToken != null) {
                                    loadDirectory(
                                        activeToken,
                                        repo,
                                        parent,
                                        selectedBranch
                                    )
                                }
                            }
                        ) {
                            Text("← Back")
                        }

                        Text(
                            if (currentPath.isBlank()) "/" else "/$currentPath",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedTextField(
                        value = entrySearch,
                        onValueChange = { entrySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search this folder") },
                        singleLine = true
                    )

                    HorizontalDivider()

                    val visibleEntries = entries.filter {
                        it.name.contains(
                            entrySearch,
                            ignoreCase = true
                        )
                    }

                    if (visibleEntries.isEmpty() && !loading) {
                        Text(
                            if (entries.isEmpty()) {
                                "This folder is empty."
                            } else {
                                "No matching files or folders."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    visibleEntries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val activeToken =
                                        token ?: return@clickable

                                    if (entry.type == "dir") {
                                        loadDirectory(
                                            activeToken,
                                            repo,
                                            entry.path,
                                            selectedBranch
                                        )
                                    } else {
                                        scope.launch {
                                            loading = true

                                            runCatching {
                                                GitHubApi.fileContent(
                                                    activeToken,
                                                    repo,
                                                    entry.path,
                                                    selectedBranch
                                                )
                                            }.onSuccess {
                                                previewTitle = entry.path
                                                previewContent = it
                                            }.onFailure {
                                                status = it.message
                                                    ?: "Could not open file."
                                            }

                                            loading = false
                                        }
                                    }
                                }
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                if (entry.type == "dir") "📁" else "📄"
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    entry.name,
                                    fontWeight = FontWeight.Medium
                                )

                                Text(
                                    if (entry.type == "dir") {
                                        "Folder"
                                    } else {
                                        "${entry.size} bytes · File"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            Text(
                                if (entry.type == "dir") "Open" else "View",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        HorizontalDivider()
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(repo.htmlUrl)
                                )
                            )
                        }
                    ) {
                        Text("Open repository on GitHub")
                    }
                }
            }
        }
    }

    if (showCreateRepository) {
        AlertDialog(
            onDismissRequest = {
                if (!loading) showCreateRepository = false
            },
            title = {
                Text("Create GitHub repository")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = newRepositoryName,
                        onValueChange = {
                            newRepositoryName = it.replace(" ", "-")
                        },
                        label = { Text("Repository name") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = newRepositoryDescription,
                        onValueChange = {
                            newRepositoryDescription = it
                        },
                        label = { Text("Description") },
                        minLines = 2
                    )

                    Row(
                        verticalAlignment =
                            androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = newRepositoryPrivate,
                            onCheckedChange = {
                                newRepositoryPrivate = it
                            }
                        )

                        Text("Private repository")
                    }

                    Text(
                        "Andro will initialize the repository and commit " +
                            ".github/workflows/andro-android-build.yml.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newRepositoryName.isNotBlank() && !loading,
                    onClick = {
                        val activeToken =
                            token ?: return@TextButton

                        scope.launch {
                            loading = true
                            status = "Creating GitHub repository..."

                            var createdRepo: GitHubRepository? = null

                            runCatching {
                                GitHubApi.createRepository(
                                    token = activeToken,
                                    name = newRepositoryName.trim(),
                                    description =
                                        newRepositoryDescription.trim(),
                                    private = newRepositoryPrivate
                                )
                            }.onSuccess { repo ->
                                createdRepo = repo
                                status = "Repository created. Installing build workflow..."

                                runCatching {
                                    GitHubApi.installBuildWorkflow(
                                        activeToken,
                                        repo
                                    )
                                }.onSuccess {
                                    status =
                                        "Repository created and Andro build workflow committed."
                                }.onFailure {
                                    status =
                                        "Repository created, but workflow commit failed: " +
                                            (it.message ?: "Unknown error")
                                }

                                repositories =
                                    runCatching {
                                        GitHubApi.repositories(activeToken)
                                    }.getOrDefault(repositories)

                                showCreateRepository = false
                                newRepositoryName = ""
                                newRepositoryDescription = ""
                            }.onFailure {
                                status = it.message
                                    ?: "Repository creation failed."
                            }

                            if (createdRepo != null) {
                                selectedRepo = null
                            }

                            loading = false
                        }
                    }
                ) {
                    Text("Create and configure")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        showCreateRepository = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (previewTitle != null) {
        AlertDialog(
            onDismissRequest = {
                previewTitle = null
                previewContent = ""
            },
            title = {
                Text(
                    previewTitle ?: "Source preview",
                    style = MaterialTheme.typography.titleSmall
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            previewContent,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        previewTitle = null
                        previewContent = ""
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}


package com.twinpower.andro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

private data class GitHubEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long
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

            require(payload.size > 12)

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
            val response = JSONArray(
                request(
                    API +
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

        val size = response.optLong("size")

        require(size <= 512 * 1024) {
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
        mutableStateOf(GitHubTokenStore.read(context))
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

    var deviceCode by remember {
        mutableStateOf<GitHubDeviceCode?>(null)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("Connect GitHub to browse your repositories.")
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
                    GitHubTokenStore.clear(context)
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
                status = it.message ?: "Could not load repository contents."
            }

            loading = false
        }
    }

    LaunchedEffect(token) {
        val activeToken = token ?: return@LaunchedEffect
        loadRepositories(activeToken)
    }

    LaunchedEffect(selectedRepo?.fullName) {
        val activeToken = token ?: return@LaunchedEffect
        val repo = selectedRepo ?: return@LaunchedEffect

        loading = true

        runCatching {
            GitHubApi.branches(activeToken, repo)
        }.onSuccess { result ->
            branches = result

            val initialBranch = result.firstOrNull {
                it == repo.defaultBranch
            } ?: result.firstOrNull() ?: repo.defaultBranch

            selectedBranch = initialBranch
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
                    "GITHUB",
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
                                    status = "Authorize Andro using this code."

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
                                            status = "GitHub connected."
                                        }.onFailure {
                                            status = it.message
                                                ?: "Authorization failed."
                                            deviceCode = null
                                        }

                                        loading = false
                                    }
                                }.onFailure {
                                    status = it.message
                                        ?: "Could not start authorization."
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
                        "YOUR DEVICE CODE",
                        fontWeight = FontWeight.Bold
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
                        Text("Authorize on GitHub")
                    }

                    Text(
                        "Keep Andro open until authorization completes.",
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
                                loadRepositories(token!!)
                            }
                        ) {
                            Text("Refresh")
                        }

                        OutlinedButton(
                            onClick = {
                                GitHubTokenStore.clear(context)
                                token = null
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
                        "REPOSITORY EXPLORER",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Choose a repository to browse its branches, folders and source files."
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
                                val parent = currentPath
                                    .substringBeforeLast("/", "")

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
                            if (currentPath.isBlank()) {
                                "/"
                            } else {
                                "/$currentPath"
                            },
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
                                    val activeToken = token ?: return@clickable

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

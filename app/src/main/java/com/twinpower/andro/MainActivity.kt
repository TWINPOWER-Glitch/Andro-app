package com.twinpower.andro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ProjectRepository(applicationContext)
        val builds = BuildManager(applicationContext, repository)
        val mcp = AndroMcpServer(repository, builds)
        setContent { AndroApp(repository, builds, mcp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroApp(
    repository: ProjectRepository,
    builds: BuildManager,
    mcp: AndroMcpServer
) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(repository.listProjects()) }
    var selected by remember { mutableStateOf<ProjectInfo?>(null) }
    var buildState by remember { mutableStateOf<BuildSnapshot?>(null) }
    var mcpRunning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Import a local Gradle project to get started.") }
    var showFiles by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("assembleDebug") }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { repository.importProject(uri) }
                .onSuccess {
                    projects = repository.listProjects()
                    selected = it
                    message = "Imported ${it.name} into Andro's local workspace."
                }
                .onFailure { message = "Project import failed: ${it.message}" }
            busy = false
        }
    }

    val toolchainPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { repository.importToolchain(uri) }
                .onSuccess { message = "Toolchain imported. Run the environment check before building." }
                .onFailure { message = "Toolchain import failed: ${it.message}" }
            busy = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF9BB4FF),
            secondary = androidx.compose.ui.graphics.Color(0xFF8ADBCB),
            background = androidx.compose.ui.graphics.Color(0xFF080D18),
            surface = androidx.compose.ui.graphics.Color(0xFF141D2D),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202B40)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("ANDRO", fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                            Text("Local Android development environment", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LOCAL WORKSPACE", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                            Text("Your projects stay on this device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Andro does not require your Vercel server or GitHub to import and manage local projects.", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { projectPicker.launch(null) }, enabled = !busy) { Text("Import project") }
                                OutlinedButton(onClick = { toolchainPicker.launch(null) }, enabled = !busy) { Text("Import toolchain") }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("BUILD ENVIRONMENT", fontWeight = FontWeight.Bold)
                            val ready = repository.toolchainStatus()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (ready.ready) "Runtime detected" else "Runtime not ready", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(if (ready.ready) "READY" else "SETUP REQUIRED",
                                    color = if (ready.ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(ready.message, style = MaterialTheme.typography.bodySmall)
                            Text("A compatible ARM64/Android toolchain is required. Desktop x86_64 SDK binaries cannot be executed directly on this phone.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Text("PROJECTS  ·  ${projects.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (projects.isEmpty()) {
                    item { Text("No projects imported yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(projects, key = { it.id }) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = project
                            message = "Selected ${project.name}"
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected?.id == project.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, fontWeight = FontWeight.Bold)
                            Text(project.rootPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (project.hasGradleWrapper) "Gradle wrapper detected" else "Gradle wrapper not found",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (selected != null) {
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("BUILD PROJECT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(selected!!.name, color = MaterialTheme.colorScheme.secondary)
                                OutlinedTextField(
                                    value = task,
                                    onValueChange = { task = it },
                                    label = { Text("Gradle task") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val p = selected ?: return@launch
                                                buildState = builds.start(p, task)
                                                message = "Local Gradle build started."
                                            }
                                        },
                                        enabled = !busy && repository.toolchainStatus().ready
                                    ) { Text("Build locally") }
                                    OutlinedButton(onClick = {
                                        files = repository.listFiles(selected!!.id)
                                        showFiles = true
                                    }) { Text("Browse files") }
                                }
                                buildState?.let { state ->
                                    Text("Build ${state.status.uppercase()}", fontWeight = FontWeight.Bold)
                                    Text(state.logs.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                                    state.artifactPaths.forEach { Text("Artifact: $it", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("BUILT-IN MCP SERVER", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Expose local Andro project and build tools to an MCP-compatible client.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (mcpRunning) "Running on 127.0.0.1:8765" else "Stopped")
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = mcpRunning,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            runCatching {
                                                if (enabled) mcp.start() else mcp.stop()
                                            }.onSuccess { mcpRunning = enabled }
                                                .onFailure { message = "MCP server error: ${it.message}" }
                                        }
                                    }
                                )
                            }
                            Text("Endpoint: http://127.0.0.1:8765/mcp", style = MaterialTheme.typography.bodySmall)
                            Text("The endpoint is loopback-only in this build. A cloud AI client needs a secure relay/tunnel to reach a phone.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("OPTIONAL CONNECTIONS", fontWeight = FontWeight.Bold)
                            Text("GitHub version control and GitHub Actions are separate optional integrations. Andro Vercel accounts/cloud services are also optional and are not required for local work.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("These connectors are not wired in this increment.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        if (showFiles) {
            AlertDialog(
                onDismissRequest = { showFiles = false },
                title = { Text("Project files") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(files) { path -> Text(path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
                    }
                },
                confirmButton = { TextButton(onClick = { showFiles = false }) { Text("Close") } }
            )
        }
    }
}

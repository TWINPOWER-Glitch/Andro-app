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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ProjectRepository(applicationContext)
        val builds = BuildManager(applicationContext, repository)
        val mcp = AndroMcpServer(repository, builds)

        setContent {
            AndroApp(repository, builds, mcp)
        }
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

    var projects by remember {
        mutableStateOf(repository.listProjects())
    }

    var selected by remember {
        mutableStateOf<ProjectInfo?>(null)
    }

    var buildState by remember {
        mutableStateOf<BuildSnapshot?>(null)
    }

    var mcpRunning by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("Create or import a project to begin.")
    }

    var task by remember {
        mutableStateOf("assembleDebug")
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var screen by remember {
        mutableStateOf("Workspace")
    }

    var showCreateProject by remember {
        mutableStateOf(false)
    }

    var showCreateFile by remember {
        mutableStateOf(false)
    }

    var showCreateDirectory by remember {
        mutableStateOf(false)
    }

    var showEditor by remember {
        mutableStateOf(false)
    }

    var projectName by remember {
        mutableStateOf("")
    }

    var newFilePath by remember {
        mutableStateOf("")
    }

    var newDirectoryPath by remember {
        mutableStateOf("")
    }

    var editingPath by remember {
        mutableStateOf("")
    }

    var editorContent by remember {
        mutableStateOf("")
    }

    var editorDirty by remember {
        mutableStateOf(false)
    }

    var files by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    fun refreshProjects() {
        projects = repository.listProjects()
        selected = selected?.let { current ->
            projects.firstOrNull { it.id == current.id }
        }
    }

    fun refreshFiles() {
        selected?.let {
            files = repository.listFiles(it.id)
        }
    }

    LaunchedEffect(buildState?.id) {
        val id = buildState?.id ?: return@LaunchedEffect

        while (true) {
            val latest = builds.get(id) ?: break
            buildState = latest

            if (
                latest.status == "succeeded" ||
                latest.status == "failed"
            ) {
                message = "Build ${latest.status}."
                break
            }

            delay(1000)
        }
    }

    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true

                runCatching {
                    repository.importProject(uri)
                }.onSuccess {
                    refreshProjects()
                    selected = it
                    refreshFiles()
                    message = "Imported ${it.name}."
                }.onFailure {
                    message = "Import failed: ${it.message}"
                }

                busy = false
            }
        }
    }

    val toolchainPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true

                runCatching {
                    repository.importToolchain(uri)
                }.onSuccess {
                    message = "Toolchain imported. Check runtime status."
                }.onFailure {
                    message = "Toolchain import failed: ${it.message}"
                }

                busy = false
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9BB4FF),
            secondary = Color(0xFF8ADBCB),
            background = Color(0xFF080D18),
            surface = Color(0xFF141D2D),
            surfaceVariant = Color(0xFF202B40)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "ANDRO",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp
                            )
                            Text(
                                "Android development environment",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        "Workspace",
                        "Editor",
                        "Builds",
                        "Connections"
                    ).forEach { destination ->
                        NavigationBarItem(
                            selected = screen == destination,
                            onClick = {
                                screen = destination
                                if (destination == "Workspace" ||
                                    destination == "Editor"
                                ) {
                                    refreshFiles()
                                }
                            },
                            icon = {
                                Text(
                                    when (destination) {
                                        "Workspace" -> "⌂"
                                        "Editor" -> "</>"
                                        "Builds" -> "⚙"
                                        else -> "⇄"
                                    }
                                )
                            },
                            label = { Text(destination) }
                        )
                    }
                }
            }
        ) { padding ->

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                when (screen) {

                    "Workspace" -> {

                        item {
                            Card(
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "LOCAL WORKSPACE",
                                        color = MaterialTheme.colorScheme.secondary,
                                        style = MaterialTheme.typography.labelLarge
                                    )

                                    Text(
                                        "Build Android projects on your device",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        "Create a project or import an existing Android project into Andro's private workspace."
                                    )

                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                showCreateProject = true
                                            },
                                            enabled = !busy
                                        ) {
                                            Text("New project")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                projectPicker.launch(null)
                                            },
                                            enabled = !busy
                                        ) {
                                            Text("Import")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            val ready = repository.toolchainStatus()

                            Card {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "BUILD ENVIRONMENT",
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        if (ready.ready) {
                                            "Runtime detected"
                                        } else {
                                            "Runtime not ready"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (ready.ready) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )

                                    Text(
                                        ready.message,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            toolchainPicker.launch(null)
                                        }
                                    ) {
                                        Text("Import toolchain")
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "YOUR PROJECTS · ${projects.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (projects.isEmpty()) {
                            item {
                                Text(
                                    "No projects yet. Create or import one.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        items(
                            projects,
                            key = { it.id }
                        ) { project ->

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = project
                                        refreshFiles()
                                        message = "Selected ${project.name}."
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor =
                                        if (selected?.id == project.id) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                )
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        project.name,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        project.rootPath,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        if (project.hasGradleWrapper) {
                                            "Gradle wrapper detected"
                                        } else {
                                            "No Gradle wrapper"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        if (selected != null) {
                            item {
                                Card {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "SELECTED PROJECT",
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            selected!!.name,
                                            color = MaterialTheme.colorScheme.secondary
                                        )

                                        Button(
                                            onClick = {
                                                screen = "Editor"
                                                refreshFiles()
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Open project editor")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                screen = "Builds"
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Open build manager")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "Editor" -> {

                        item {
                            Text(
                                "SOURCE EDITOR",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                selected?.name
                                    ?: "Select a project in Workspace first."
                            )
                        }

                        if (selected != null) {

                            item {
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            showCreateFile = true
                                        }
                                    ) {
                                        Text("New file")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            showCreateDirectory = true
                                        }
                                    ) {
                                        Text("New folder")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            refreshFiles()
                                        }
                                    ) {
                                        Text("Refresh")
                                    }
                                }
                            }

                            if (files.isEmpty()) {
                                item {
                                    Text("This project has no visible files.")
                                }
                            }

                            items(
                                files,
                                key = { it }
                            ) { path ->

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                runCatching {
                                                    repository.readFile(
                                                        selected!!.id,
                                                        path
                                                    )
                                                }.onSuccess { content ->
                                                    editingPath = path
                                                    editorContent = content
                                                    editorDirty = false
                                                    showEditor = true
                                                }.onFailure {
                                                    message =
                                                        "Could not open file: ${it.message}"
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        Text(
                                            path,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium
                                        )

                                        Text(
                                            "OPEN",
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "Builds" -> {

                        item {
                            Text(
                                "BUILD MANAGER",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        item {
                            if (selected == null) {
                                Text("Select a project in Workspace first.")
                            } else {
                                Card {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement =
                                            Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            selected!!.name,
                                            fontWeight = FontWeight.Bold
                                        )

                                        OutlinedTextField(
                                            value = task,
                                            onValueChange = {
                                                task = it
                                            },
                                            label = {
                                                Text("Gradle task")
                                            },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    busy = true

                                                    runCatching {
                                                        builds.start(
                                                            selected!!,
                                                            task
                                                        )
                                                    }.onSuccess {
                                                        buildState = it
                                                        message = "Build queued."
                                                    }.onFailure {
                                                        message =
                                                            "Build could not start: ${it.message}"
                                                    }

                                                    busy = false
                                                }
                                            },
                                            enabled = !busy &&
                                                repository.toolchainStatus().ready,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Start local build")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            buildState?.let { state ->

                                Card {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "BUILD ${state.status.uppercase()}",
                                            fontWeight = FontWeight.Bold,
                                            color = when (state.status) {
                                                "succeeded" ->
                                                    MaterialTheme.colorScheme.secondary
                                                "failed" ->
                                                    MaterialTheme.colorScheme.error
                                                else ->
                                                    MaterialTheme.colorScheme.primary
                                            }
                                        )

                                        Text("Task: ${state.task}")
                                        Text("Build ID: ${state.id}")

                                        if (state.exitCode != null) {
                                            Text("Exit code: ${state.exitCode}")
                                        }

                                        HorizontalDivider()

                                        Text(
                                            "BUILD LOG",
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            state.logs.takeLast(100)
                                                .joinToString("\n"),
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        state.artifactPaths.forEach {
                                            Text(
                                                "Artifact: $it",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            } ?: item {
                                Text("No build has been started in this session.")
                            }
                        }
                    }

                    "Connections" -> {

                        item {
                            Text(
                                "CONNECTIONS",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        item {
                            Card {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "LOCAL MCP SERVER",
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        "Expose local project and build tools to an MCP-compatible client."
                                    )

                                    Row(
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (mcpRunning) {
                                                "Running · 127.0.0.1:8765"
                                            } else {
                                                "Stopped"
                                            }
                                        )

                                        Spacer(Modifier.weight(1f))

                                        Switch(
                                            checked = mcpRunning,
                                            onCheckedChange = { enabled ->
                                                scope.launch {
                                                    runCatching {
                                                        if (enabled) {
                                                            mcp.start()
                                                        } else {
                                                            mcp.stop()
                                                        }
                                                    }.onSuccess {
                                                        mcpRunning = enabled
                                                    }.onFailure {
                                                        message =
                                                            "MCP error: ${it.message}"
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    Text(
                                        "http://127.0.0.1:8765/mcp",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        "This endpoint is accessible only from the phone itself. External AI clients need a separately secured remote connection.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        item {
                            Card {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "GITHUB",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "GitHub repository authorization and synchronization are not implemented in this phase."
                                    )
                                }
                            }
                        }

                        item {
                            Card {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "ANDRO-SERVER",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Remote build-server connection is not implemented in this phase."
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showCreateProject) {
            AlertDialog(
                onDismissRequest = {
                    showCreateProject = false
                },
                title = {
                    Text("Create Android project")
                },
                text = {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = {
                            projectName = it
                        },
                        label = {
                            Text("Project name")
                        },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true

                                runCatching {
                                    repository.createProject(projectName)
                                }.onSuccess {
                                    refreshProjects()
                                    selected = it
                                    refreshFiles()
                                    projectName = ""
                                    screen = "Editor"
                                    message = "Project created."
                                }.onFailure {
                                    message =
                                        "Project creation failed: ${it.message}"
                                }

                                busy = false
                                showCreateProject = false
                            }
                        },
                        enabled = projectName.isNotBlank() && !busy
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCreateProject = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showCreateFile) {
            AlertDialog(
                onDismissRequest = {
                    showCreateFile = false
                },
                title = {
                    Text("Create source file")
                },
                text = {
                    OutlinedTextField(
                        value = newFilePath,
                        onValueChange = {
                            newFilePath = it
                        },
                        label = {
                            Text("File path")
                        },
                        placeholder = {
                            Text("app/src/main/java/MainActivity.kt")
                        },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                repository.createFile(
                                    selected!!.id,
                                    newFilePath
                                )
                            }.onSuccess {
                                refreshFiles()
                                message = "File created."
                                newFilePath = ""
                            }.onFailure {
                                message =
                                    "File creation failed: ${it.message}"
                            }

                            showCreateFile = false
                        },
                        enabled = newFilePath.isNotBlank()
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCreateFile = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showCreateDirectory) {
            AlertDialog(
                onDismissRequest = {
                    showCreateDirectory = false
                },
                title = {
                    Text("Create folder")
                },
                text = {
                    OutlinedTextField(
                        value = newDirectoryPath,
                        onValueChange = {
                            newDirectoryPath = it
                        },
                        label = {
                            Text("Folder path")
                        },
                        placeholder = {
                            Text("app/src/main/java")
                        },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                repository.createDirectory(
                                    selected!!.id,
                                    newDirectoryPath
                                )
                            }.onSuccess {
                                refreshFiles()
                                message = "Folder created."
                                newDirectoryPath = ""
                            }.onFailure {
                                message =
                                    "Folder creation failed: ${it.message}"
                            }

                            showCreateDirectory = false
                        },
                        enabled = newDirectoryPath.isNotBlank()
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCreateDirectory = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showEditor) {
            AlertDialog(
                onDismissRequest = {
                    showEditor = false
                },
                title = {
                    Text(editingPath)
                },
                text = {
                    OutlinedTextField(
                        value = editorContent,
                        onValueChange = {
                            editorContent = it
                            editorDirty = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 250.dp, max = 480.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = {
                            Text("Edit source code...")
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                repository.writeFile(
                                    selected!!.id,
                                    editingPath,
                                    editorContent
                                )
                            }.onSuccess {
                                editorDirty = false
                                refreshFiles()
                                message = "Saved $editingPath."
                                showEditor = false
                            }.onFailure {
                                message =
                                    "Save failed: ${it.message}"
                            }
                        },
                        enabled = editorDirty
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showEditor = false
                        }
                    ) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

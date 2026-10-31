
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ProjectRepository(applicationContext)
        val builds = BuildManager(applicationContext, repository)

        // Shared GitHub build bridge for MCP requests.
        // The bridge reads credentials from GitHubSecureStore.
        val githubBuilds = GitHubMcpBuildService(
            applicationContext,
            repository
        )

        val mcp = AndroMcpServer(
            repository,
            builds,
            githubBuilds
        )

        setContent {
            AndroApp(
                repository = repository,
                builds = builds,
                mcp = mcp
            )
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

    var files by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var mcpRunning by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("Create or import a project to begin.")
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

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed
    )

    fun refreshProjects() {
        projects = repository.listProjects()

        selected = selected?.let { current ->
            projects.firstOrNull { it.id == current.id }
        }
    }

    fun refreshFiles(projectId: String? = selected?.id) {
        files = if (projectId == null) {
            emptyList()
        } else {
            repository.listFiles(projectId)
        }
    }

    fun navigateTo(destination: String) {
        screen = destination

        if (
            destination == "Workspace" ||
            destination == "Editor"
        ) {
            refreshFiles()
        }

        scope.launch {
            drawerState.close()
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
                }.onSuccess { project ->
                    refreshProjects()
                    selected = project
                    refreshFiles(project.id)
                    message = "Imported ${project.name}."
                }.onFailure {
                    message = "Import failed: ${it.message}"
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

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor =
                        MaterialTheme.colorScheme.surface,
                    drawerTonalElevation = 4.dp
                ) {

                    // Drawer header.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 20.dp,
                                vertical = 24.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "ANDRO",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            "Android development environment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }

                    // Main navigation destinations.
                    NavigationDrawerItem(
                        label = {
                            Text("Workspace")
                        },
                        selected = screen == "Workspace",
                        onClick = {
                            navigateTo("Workspace")
                        },
                        icon = {
                            Text("⌂")
                        },
                        modifier = Modifier.padding(
                            NavigationDrawerItemDefaults.ItemPadding
                        )
                    )

                    NavigationDrawerItem(
                        label = {
                            Text("Editor")
                        },
                        selected = screen == "Editor",
                        onClick = {
                            navigateTo("Editor")
                        },
                        icon = {
                            Text("</>")
                        },
                        modifier = Modifier.padding(
                            NavigationDrawerItemDefaults.ItemPadding
                        )
                    )

                    NavigationDrawerItem(
                        label = {
                            Text("Builds")
                        },
                        selected = screen == "Builds",
                        onClick = {
                            navigateTo("Builds")
                        },
                        icon = {
                            Text("⚙")
                        },
                        modifier = Modifier.padding(
                            NavigationDrawerItemDefaults.ItemPadding
                        )
                    )

                    NavigationDrawerItem(
                        label = {
                            Text("Connections")
                        },
                        selected = screen == "Connections",
                        onClick = {
                            navigateTo("Connections")
                        },
                        icon = {
                            Text("⇄")
                        },
                        modifier = Modifier.padding(
                            NavigationDrawerItemDefaults.ItemPadding
                        )
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = 20.dp,
                            vertical = 16.dp
                        ),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Workspace information.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "WORKSPACE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Projects: ${projects.size}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            selected?.let {
                                "Selected: ${it.name}"
                            } ?: "No project selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            "Build engine: GitHub Actions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        "ANDRO · Development workspace",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {

            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }
                            ) {
                                Text(
                                    "☰",
                                    fontSize = 25.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
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

                                    if (
                                        destination == "Workspace" ||
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
                                label = {
                                    Text(destination)
                                }
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            "LOCAL WORKSPACE",
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelLarge
                                        )

                                        Text(
                                            "Your Android development workspace",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            "Create a project or import an existing Android project into Andro's private workspace."
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                Card {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            "BUILD SYSTEM",
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            "GitHub Actions",
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Text(
                                            "Andro uses GitHub Actions for remote Android builds. Your project files remain available in the local workspace."
                                        )
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
                                            refreshFiles(project.id)
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
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
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
                                                "No Gradle wrapper detected"
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
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                Text("Open remote build manager")
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
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                path,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium
                                            )

                                            Text(
                                                "Open",
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }

                            } else {
                                item {
                                    Text("Create or import a project in Workspace first.")
                                }
                            }
                        }

                        "Builds" -> {

                            item {
                                Text(
                                    "REMOTE BUILD MANAGER",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            item {
                                GitHubRemoteBuilds(
                                    repository = repository
                                )
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "LOCAL MCP SERVER",
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            "Expose local project and build tools to an MCP-compatible client."
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
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
                                CloudflareMcpConnections(
                                    mcp = mcp,
                                    onLocalServerStarted = {
                                        mcpRunning = true
                                    }
                                )
                            }

                            item {
                                GitHubConnections()
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
                                }.onSuccess { project ->
                                    refreshProjects()
                                    selected = project
                                    refreshFiles(project.id)
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
                        enabled = newFilePath.isNotBlank() && selected != null
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
                        enabled = newDirectoryPath.isNotBlank() &&
                            selected != null
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
                            .heightIn(
                                min = 250.dp,
                                max = 480.dp
                            ),
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
                                message = "Save failed: ${it.message}"
                            }
                        },
                        enabled = editorDirty && selected != null
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

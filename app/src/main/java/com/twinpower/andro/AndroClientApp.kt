
package com.twinpower.andro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroClientApp(
    repository: ProjectRepository,
    mcp: AndroMcpServer
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val draftStore = remember(context) { EditorDraftStore(context) }

    var projects by remember {
        mutableStateOf(emptyList<ProjectInfo>())
    }

    var projectsLoading by remember { mutableStateOf(true) }
    var projectListRequest by remember { mutableIntStateOf(0) }

    var selected by remember {
        mutableStateOf<ProjectInfo?>(null)
    }

    var selectedProjectId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var files by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var filesLoading by remember { mutableStateOf(false) }
    var fileListRequest by remember { mutableIntStateOf(0) }
    var openFileRequest by remember { mutableIntStateOf(0) }

    var tab by rememberSaveable {
        mutableStateOf("Projects")
    }

    var busy by remember {
        mutableStateOf(false)
    }
    var workspaceOperation by remember { mutableStateOf<String?>(null) }

    var mcpRunning by remember {
        mutableStateOf(false)
    }

    var mcpBusy by remember {
        mutableStateOf(false)
    }

    var showCreateProject by remember { mutableStateOf(false) }
    var showCreateFile by remember { mutableStateOf(false) }
    var showCreateDirectory by remember { mutableStateOf(false) }
    var showRenameEntry by remember { mutableStateOf(false) }
    var showDeleteEntry by remember { mutableStateOf(false) }
    var showDiscardChanges by remember { mutableStateOf(false) }

    var projectName by remember { mutableStateOf("") }
    var newFilePath by remember { mutableStateOf("") }
    var newDirectoryPath by remember { mutableStateOf("") }

    var entryPath by remember { mutableStateOf("") }
    var newEntryName by remember { mutableStateOf("") }

    var editingPath by rememberSaveable { mutableStateOf("") }
    var editingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorBaseContent by remember { mutableStateOf("") }
    var editorDirty by remember { mutableStateOf(false) }
    var draftStatus by remember { mutableStateOf("No unsaved changes") }
    var draftErrorShown by remember { mutableStateOf(false) }
    var editorLoadedKey by remember { mutableStateOf<String?>(null) }
    var isLoadingFile by remember {
        mutableStateOf(editingProjectId != null && editingPath.isNotBlank())
    }
    var isSavingFile by remember { mutableStateOf(false) }
    var fileOperationBusy by remember { mutableStateOf(false) }
    var draftConflict by remember { mutableStateOf<EditorDraft?>(null) }
    var fileConflict by remember { mutableStateOf<EditorFileConflict?>(null) }
    val latestEditorSnapshot by rememberUpdatedState(
        Triple(
            editingProjectId,
            editingPath,
            Triple(editorBaseContent, editorContent, editorDirty)
        )
    )

    var pendingOpenPath by remember { mutableStateOf<String?>(null) }
    var pendingProject by remember { mutableStateOf<ProjectInfo?>(null) }
    var pendingTab by remember { mutableStateOf("Code") }

    fun showMessage(message: String) {
        scope.launch {
            snackbar.showSnackbar(message)
        }
    }

    fun refreshProjects() {
        val request = ++projectListRequest
        scope.launch {
            projectsLoading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.listProjects()
                }
            }.onSuccess { loaded ->
                if (request == projectListRequest) {
                    projects = loaded
                    val currentId = selected?.id ?: selectedProjectId
                    selected = loaded.firstOrNull { it.id == currentId }
                    selectedProjectId = selected?.id
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (request == projectListRequest) {
                    showMessage("Could not load projects: ${it.message}")
                }
            }
            if (request == projectListRequest) projectsLoading = false
        }
    }

    fun refreshFiles(projectId: String? = selected?.id) {
        val request = ++fileListRequest
        if (projectId == null) {
            files = emptyList()
            filesLoading = false
            return
        }

        scope.launch {
            filesLoading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.listFiles(projectId)
                }
            }.onSuccess { loaded ->
                if (request == fileListRequest) files = loaded
            }.onFailure {
                if (it is CancellationException) throw it
                if (request == fileListRequest) {
                    files = emptyList()
                    showMessage("Could not list project files: ${it.message}")
                }
            }
            if (request == fileListRequest) filesLoading = false
        }
    }

    fun clearEditor() {
        openFileRequest++
        isLoadingFile = false
        fileConflict = null
        val projectId = editingProjectId
        val path = editingPath
        if (projectId != null && path.isNotBlank()) {
            draftStore.markDirty(projectId, path, false)
            scope.launch {
                runCatching { draftStore.delete(projectId, path) }
            }
        }

        editingPath = ""
        editingProjectId = null
        editorContent = ""
        editorBaseContent = ""
        editorDirty = false
        draftStatus = "No unsaved changes"
        editorLoadedKey = null
        draftConflict = null
    }

    fun selectProject(
        project: ProjectInfo,
        destination: String
    ) {
        if (isSavingFile) {
            showMessage("Wait for the current file save to finish.")
            return
        }

        if ((editorDirty || draftConflict != null) &&
            selected?.id != project.id
        ) {
            pendingProject = project
            pendingTab = destination
            pendingOpenPath = null
            showDiscardChanges = true
            return
        }

        if (selected?.id != project.id) {
            clearEditor()
        }

        selected = project
        selectedProjectId = project.id
        refreshFiles(project.id)
        tab = destination

        showMessage("Opened ${project.name}.")
    }

    fun openFile(
        path: String,
        projectIdOverride: String? = null
    ) {
        val projectId = projectIdOverride ?: selected?.id ?: return
        val key = "$projectId:$path"

        if (editorLoadedKey == key) return

        if ((editorDirty || draftConflict != null) &&
            (editingProjectId != projectId || editingPath != path)
        ) {
            pendingOpenPath = path
            pendingProject = null
            showDiscardChanges = true
            return
        }

        val request = ++openFileRequest
        scope.launch {
            isLoadingFile = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val content = repository.readFile(projectId, path)
                    val draftResult = runCatching {
                        draftStore.read(projectId, path)
                    }
                    val draftError = draftResult.exceptionOrNull()
                    if (draftError is CancellationException) throw draftError
                    Triple(content, draftResult.getOrNull(), draftError)
                }
            }.onSuccess { (content, draft, draftError) ->
                if (request != openFileRequest) return@onSuccess
                editingPath = path
                editingProjectId = projectId
                selectedProjectId = projectId
                editorBaseContent = content
                editorContent = content
                editorDirty = false
                editorLoadedKey = key

                if (draftError != null) {
                    draftStore.markDirty(projectId, path, false)
                    draftStore.delete(projectId, path)
                    showMessage(
                        "A saved draft could not be recovered. The last saved file was opened."
                    )
                }

                if (draft != null) {
                    if (draftStore.matchesCurrentFile(draft, content)) {
                        editorContent = draft.content
                        editorDirty = draft.content != content
                        draftStore.markDirty(projectId, path, editorDirty)
                        if (!editorDirty) {
                            draftStore.delete(projectId, path)
                        } else {
                            showMessage("Recovered unsaved changes for $path.")
                        }
                    } else {
                        draftStore.markDirty(projectId, path, true)
                        draftConflict = draft
                    }
                } else {
                    draftStore.markDirty(projectId, path, false)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (request == openFileRequest) {
                    if (editorLoadedKey != key) {
                        editingPath = ""
                        editingProjectId = null
                        editorContent = ""
                        editorBaseContent = ""
                        editorDirty = false
                    }
                    showMessage("Could not open file: ${it.message}")
                }
            }
            if (request == openFileRequest) isLoadingFile = false
        }
    }

    fun closeEditor() {
        if (isSavingFile) {
            showMessage("Wait for the current file save to finish.")
            return
        }

        if (editorDirty || draftConflict != null) {
            pendingOpenPath = ""
            pendingProject = null
            showDiscardChanges = true
        } else {
            clearEditor()
        }
    }

    fun saveFile(overwriteIfCurrentIs: String? = null) {
        val projectId = editingProjectId ?: return
        val path = editingPath
        val baseContent = editorBaseContent
        val contentToSave = editorContent

        scope.launch {
            isSavingFile = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val currentContent = repository.writeFileIfUnchanged(
                        projectId = projectId,
                        relativePath = path,
                        expectedContent = overwriteIfCurrentIs ?: baseContent,
                        newContent = contentToSave
                    )
                    if (currentContent != null) {
                        throw EditorFileChangedException(currentContent)
                    }
                }
            }.onSuccess {
                editorBaseContent = contentToSave
                if (editorContent == contentToSave) editorDirty = false
                if (!editorDirty) draftStore.delete(projectId, path)
                draftStore.markDirty(projectId, path, editorDirty)
                fileConflict = null
                refreshFiles(projectId)
                showMessage("Saved $path.")
            }.onFailure {
                if (it is CancellationException) throw it
                if (it is EditorFileChangedException) {
                    fileConflict = EditorFileConflict(
                        projectId = projectId,
                        path = path,
                        latestContent = it.latestContent
                    )
                } else {
                    showMessage("Save failed: ${it.message}")
                }
            }
            isSavingFile = false
        }
    }

    DisposableEffect(
        lifecycleOwner,
        draftStore,
        editingProjectId,
        editingPath
    ) {
        val projectId = editingProjectId
        val path = editingPath
        var persistedAtStop = false

        fun persistLatestDraft() {
            val snapshot = latestEditorSnapshot
            if (projectId != null && path.isNotBlank() &&
                snapshot.first == projectId &&
                snapshot.second == path &&
                snapshot.third.third
            ) {
                draftStore.persistNow(
                    projectId,
                    path,
                    snapshot.third.first,
                    snapshot.third.second
                )
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> persistedAtStop = false
                Lifecycle.Event.ON_STOP -> {
                    persistLatestDraft()
                    persistedAtStop = true
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!persistedAtStop) persistLatestDraft()
        }
    }

    val projectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                workspaceOperation = "Importing project…"

                runCatching {
                    repository.importProject(uri)
                }.onSuccess { project ->
                    refreshProjects()
                    selectProject(project, "Code")
                    showMessage("Imported ${project.name}.")
                }.onFailure {
                    showMessage("Import failed: ${it.message}")
                }

                busy = false
                workspaceOperation = null
            }
        }
    }

    LaunchedEffect(projectsLoading, projects, selectedProjectId) {
        if (!projectsLoading) {
            selected = selectedProjectId?.let { id ->
                projects.firstOrNull { it.id == id }
            }

            selected?.let { project ->
                refreshFiles(project.id)
            }
        }
    }

    LaunchedEffect(projectsLoading, editingProjectId, editingPath) {
        val projectId = editingProjectId
        if (!projectsLoading && projectId != null && editingPath.isNotBlank()) {
            openFile(editingPath, projectId)
        }
    }

    LaunchedEffect(
        editingProjectId,
        editingPath,
        editorContent,
        editorBaseContent,
        editorDirty,
        draftConflict
    ) {
        val projectId = editingProjectId
        val path = editingPath
        if (projectId == null || path.isBlank()) return@LaunchedEffect

        val conflictMatchesFile = draftConflict?.let {
            it.projectId == projectId && it.path == path
        } == true

        if (!editorDirty) {
            if (!conflictMatchesFile) {
                draftStore.delete(projectId, path)
                draftStatus = "No unsaved changes"
            } else {
                draftStatus = "Draft needs review"
            }
            return@LaunchedEffect
        }

        draftStatus = "Saving recovery copy…"
        delay(300)
        runCatching {
            draftStore.save(
                projectId = projectId,
                path = path,
                baseContent = editorBaseContent,
                content = editorContent
            )
        }.onSuccess {
            draftStatus = "Recovery copy saved"
            draftErrorShown = false
        }.onFailure {
            if (it is CancellationException) throw it
            draftStatus = "Recovery copy failed"
            if (!draftErrorShown) {
                showMessage("Could not preserve the unsaved draft: ${it.message}")
                draftErrorShown = true
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProjects()
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3157D5),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE4FF),
            onPrimaryContainer = Color(0xFF142B70),
            secondary = Color(0xFF167C72),
            background = Color(0xFFF5F7FB),
            surface = Color.White,
            surfaceVariant = Color(0xFFECEFF5),
            error = Color(0xFFB3261E)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,

            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (tab) {
                                    "Projects" -> "Projects"
                                    "Code" -> "Code workspace"
                                    "Build" -> "Remote builds"
                                    else -> "Connections"
                                },
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                selected?.name
                                    ?: "Android development environment",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },

            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    val destinations = listOf(
                        Triple("Projects", "▦", "Projects"),
                        Triple("Code", "</>", "Code"),
                        Triple("Build", "⚙", "Build"),
                        Triple("Connect", "⇄", "Connect")
                    )

                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination.first,
                            onClick = {
                                tab = destination.first

                                if (
                                    destination.first == "Code" ||
                                    destination.first == "Projects"
                                ) {
                                    refreshFiles()
                                }
                            },
                            icon = {
                                Text(destination.second)
                            },
                            label = {
                                Text(destination.third)
                            }
                        )
                    }
                }
            },

            snackbarHost = {
                SnackbarHost(hostState = snackbar)
            }

        ) { padding ->

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                if (busy) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                workspaceOperation ?: "Working…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (fileOperationBusy) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // -------------------------------------------------
                // PROJECTS
                // -------------------------------------------------

                if (tab == "Projects") {

                    item {
                        ClientPageIntro(
                            eyebrow = "ANDRO WORKSPACE",
                            title = "Build Android projects from one place",
                            description =
                                "Create a project or import an existing one. " +
                                "Then edit its source files and use GitHub Actions " +
                                "for remote Android builds."
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    showCreateProject = true
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("New project")
                            }

                            OutlinedButton(
                                onClick = {
                                    projectPicker.launch(null)
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Import project")
                            }
                        }
                    }

                    item {
                        ClientSectionHeading(
                            title = "Your projects",
                            detail = "${projects.size} available"
                        )
                    }

                    if (projects.isEmpty()) {
                        item {
                            if (projectsLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                            ClientEmptyState(
                                title = "No projects yet",
                                description =
                                    "Create a new Android project or import " +
                                    "an existing project to get started."
                            )
                            }
                        }
                    }

                    items(
                        projects,
                        key = { it.id }
                    ) { project ->

                        ProjectCard(
                            project = project,
                            selected = selected?.id == project.id,
                            onOpen = {
                                selectProject(project, "Code")
                            },
                            onBuild = {
                                selectProject(project, "Build")
                            }
                        )
                    }

                    if (selected != null) {
                        item {
                            ClientInfoCard(
                                title = "Current project",
                                description =
                                    "${selected!!.name} is currently selected. " +
                                    "Open its code or continue to remote builds."
                            ) {
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            tab = "Code"
                                            refreshFiles()
                                        }
                                    ) {
                                        Text("Open code")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            tab = "Build"
                                        }
                                    ) {
                                        Text("Build")
                                    }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------------
                // CODE WORKSPACE
                // -------------------------------------------------

                if (tab == "Code") {

                    item {
                        ClientPageIntro(
                            eyebrow = "SOURCE CODE",
                            title = "Project files and editor",
                            description =
                                "Choose a source file to edit it. " +
                                "Changes are kept in the selected project's " +
                                "local workspace until you save."
                        )
                    }

                    if (selected == null) {

                        item {
                            ClientEmptyState(
                                title = "Select a project first",
                                description =
                                    "Your files belong to a project. " +
                                    "Choose or create a project before opening " +
                                    "the source editor."
                            )
                        }

                        item {
                            Button(
                                onClick = {
                                    tab = "Projects"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Go to projects")
                            }
                        }

                    } else {

                        item {
                            ClientInfoCard(
                                title = selected!!.name,
                                description =
                                    "${files.size} visible files · " +
                                    selected!!.rootPath
                            ) {
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

                                    TextButton(
                                        onClick = {
                                            refreshFiles()
                                        }
                                    ) {
                                        Text("Refresh")
                                    }
                                }
                            }
                        }

                        if (editingPath.isNotBlank()) {
                            item {
                                EditorPanel(
                                    path = editingPath,
                                    content = editorContent,
                                    dirty = editorDirty,
                                    loading = isLoadingFile,
                                    saving = isSavingFile,
                                    draftStatus = draftStatus,
                                    onContentChange = {
                                        editorContent = it
                                        editorDirty = it != editorBaseContent
                                        editingProjectId?.let { projectId ->
                                            if (editingPath.isNotBlank()) {
                                                draftStore.markDirty(
                                                    projectId,
                                                    editingPath,
                                                    editorDirty
                                                )
                                            }
                                        }
                                    },
                                    onSave = {
                                        saveFile()
                                    },
                                    onClose = {
                                        closeEditor()
                                    }
                                )
                            }
                        }

                        item {
                            ClientSectionHeading(
                                title = "Project files",
                                detail = "Select a file to edit"
                            )
                        }

                        if (filesLoading) {
                            item {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else if (files.isEmpty()) {
                            item {
                                ClientEmptyState(
                                    title = "No visible files",
                                    description =
                                        "Create a source file or check that " +
                                        "the selected project contains files."
                                )
                            }
                        }

                        items(
                            files,
                            key = { it }
                        ) { path ->

                            FileEntryCard(
                                path = path,
                                active = editingPath == path,
                                onOpen = {
                                    openFile(path)
                                },
                                onRename = {
                                    entryPath = path
                                    newEntryName =
                                        path.substringAfterLast("/")
                                    showRenameEntry = true
                                },
                                onDelete = {
                                    entryPath = path
                                    showDeleteEntry = true
                                }
                            )
                        }
                    }
                }

                // -------------------------------------------------
                // REMOTE BUILDS
                // -------------------------------------------------

                if (tab == "Build") {

                    item {
                        ClientPageIntro(
                            eyebrow = "ANDROID BUILD PIPELINE",
                            title = "Build your Android application",
                            description =
                                "Andro uses GitHub Actions to run remote " +
                                "Android builds. Configure the project and " +
                                "repository connection in the build manager."
                        )
                    }

                    item {
                        ClientInfoCard(
                            title = "Remote build system",
                            description =
                                "Build execution is handled by GitHub Actions. " +
                                "The phone manages the project and communicates " +
                                "with the remote build workflow."
                        )
                    }

                    item {
                        GitHubRemoteBuilds(
                            repository = repository
                        )
                    }
                }

                // -------------------------------------------------
                // CONNECTIONS
                // -------------------------------------------------

                if (tab == "Connect") {

                    item {
                        ClientPageIntro(
                            eyebrow = "INTEGRATIONS",
                            title = "Connect Andro to other services",
                            description =
                                "These connections serve different purposes. " +
                                "GitHub manages repositories and remote builds. " +
                                "Cloudflare provides the remote MCP relay. " +
                                "The local MCP server exposes tools on this device."
                        )
                    }

                    item {
                        ClientInfoCard(
                            title = "Local MCP server",
                            description =
                                "Makes Andro's project and build tools available " +
                                "to MCP-compatible software running on this device."
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (mcpRunning) "Running" else "Stopped",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (mcpRunning) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )

                                    Text(
                                        "127.0.0.1:8765",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Switch(
                                    checked = mcpRunning,
                                    enabled = !mcpBusy,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            mcpBusy = true

                                            runCatching {
                                                if (enabled) {
                                                    mcp.start()
                                                } else {
                                                    mcp.stop()
                                                }
                                            }.onSuccess {
                                                mcpRunning = enabled
                                                showMessage(
                                                    if (enabled) {
                                                        "Local MCP server started."
                                                    } else {
                                                        "Local MCP server stopped."
                                                    }
                                                )
                                            }.onFailure {
                                                showMessage(
                                                    "MCP operation failed: ${it.message}"
                                                )
                                            }

                                            mcpBusy = false
                                        }
                                    }
                                )
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            Text(
                                "Local endpoint",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                "http://127.0.0.1:8765/mcp",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                "This address is local to the device. " +
                                "External clients require the separately " +
                                "configured remote connection.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item {
                        ClientSectionHeading(
                            title = "Cloudflare relay",
                            detail = "Remote MCP connectivity"
                        )

                        CloudflareMcpConnections(
                            mcp = mcp,
                            onLocalServerStarted = {
                                mcpRunning = true
                            }
                        )
                    }

                    item {
                        ClientSectionHeading(
                            title = "GitHub",
                            detail = "Repository access and builds"
                        )

                        GitHubConnections()
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // CREATE PROJECT
        // ---------------------------------------------------------

        if (showCreateProject) {
            AlertDialog(
                onDismissRequest = {
                    showCreateProject = false
                },
                title = {
                    Text("Create Android project")
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Choose a name for your new project."
                        )

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
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = projectName.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                workspaceOperation = "Creating project…"

                                runCatching {
                                    repository.createProject(projectName)
                                }.onSuccess { project ->
                                    refreshProjects()
                                    projectName = ""
                                    showCreateProject = false
                                    selectProject(project, "Code")
                                    showMessage("Project created.")
                                }.onFailure {
                                    showMessage(
                                        "Project creation failed: ${it.message}"
                                    )
                                }

                                busy = false
                                workspaceOperation = null
                            }
                        }
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

        // ---------------------------------------------------------
        // CREATE FILE
        // ---------------------------------------------------------

        if (showCreateFile) {
            AlertDialog(
                onDismissRequest = {
                    showCreateFile = false
                },
                title = {
                    Text("Create source file")
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Enter a project-relative path. " +
                            "Folders in the path will be created when necessary."
                        )

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
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newFilePath.isNotBlank() &&
                            selected != null && !fileOperationBusy,
                        onClick = {
                            val projectId = selected?.id

                            if (projectId != null) {
                                val path = newFilePath.trim()

                                scope.launch {
                                    fileOperationBusy = true
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            repository.createFile(projectId, path)
                                        }
                                    }.onSuccess {
                                        refreshFiles(projectId)
                                        newFilePath = ""
                                        showCreateFile = false
                                        openFile(path)
                                        showMessage("Created $path.")
                                    }.onFailure {
                                        if (it is CancellationException) throw it
                                        showMessage("File creation failed: ${it.message}")
                                    }
                                    fileOperationBusy = false
                                }
                            }
                        }
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

        // ---------------------------------------------------------
        // CREATE FOLDER
        // ---------------------------------------------------------

        if (showCreateDirectory) {
            AlertDialog(
                onDismissRequest = {
                    showCreateDirectory = false
                },
                title = {
                    Text("Create folder")
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Enter the folder path relative to the project root."
                        )

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
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newDirectoryPath.isNotBlank() &&
                            selected != null && !fileOperationBusy,
                        onClick = {
                            val projectId = selected?.id

                            if (projectId != null) {
                                val path = newDirectoryPath.trim()
                                scope.launch {
                                    fileOperationBusy = true
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            repository.createDirectory(projectId, path)
                                        }
                                    }.onSuccess {
                                        refreshFiles(projectId)
                                        newDirectoryPath = ""
                                        showCreateDirectory = false
                                        showMessage("Folder created.")
                                    }.onFailure {
                                        if (it is CancellationException) throw it
                                        showMessage("Folder creation failed: ${it.message}")
                                    }
                                    fileOperationBusy = false
                                }
                            }
                        }
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

        // ---------------------------------------------------------
        // RENAME FILE
        // ---------------------------------------------------------

        if (showRenameEntry) {
            AlertDialog(
                onDismissRequest = {
                    showRenameEntry = false
                },
                title = {
                    Text("Rename file")
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            entryPath,
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = newEntryName,
                            onValueChange = {
                                newEntryName = it
                            },
                            label = {
                                Text("New file name")
                            },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newEntryName.isNotBlank() &&
                            selected != null && !fileOperationBusy,
                        onClick = {
                            val projectId = selected?.id
                            val oldPath = entryPath
                            val newName = newEntryName.trim()

                            if (projectId != null) {
                                if (editingProjectId == projectId &&
                                    editingPath == oldPath &&
                                    (editorDirty || draftConflict != null)
                                ) {
                                    showMessage("Save or discard this file's edits before renaming it.")
                                } else {
                                    scope.launch {
                                        fileOperationBusy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                repository.renameEntry(projectId, oldPath, newName)
                                            }
                                        }.onSuccess {
                                            val parent = oldPath.substringBeforeLast("/", "")
                                            val newPath = if (parent.isBlank()) newName else "$parent/$newName"

                                            if (editingProjectId == projectId && editingPath == oldPath) {
                                                scope.launch { draftStore.delete(projectId, oldPath) }
                                                editingPath = newPath
                                                editorLoadedKey = "$projectId:$newPath"
                                            }

                                            refreshFiles(projectId)
                                            showRenameEntry = false
                                            entryPath = ""
                                            newEntryName = ""
                                            showMessage("Renamed to $newPath.")
                                        }.onFailure {
                                            if (it is CancellationException) throw it
                                            showMessage("Rename failed: ${it.message}")
                                        }
                                        fileOperationBusy = false
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showRenameEntry = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ---------------------------------------------------------
        // DELETE FILE
        // ---------------------------------------------------------

        if (showDeleteEntry) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteEntry = false
                },
                title = {
                    Text("Delete file?")
                },
                text = {
                    Text(
                        "Permanently delete \"$entryPath\"? " +
                        "This operation cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = selected != null && !fileOperationBusy,
                        onClick = {
                            val projectId = selected?.id

                            if (projectId != null) {
                                val path = entryPath
                                if (editingProjectId == projectId &&
                                    editingPath == path &&
                                    (editorDirty || draftConflict != null)
                                ) {
                                    showMessage("Save or discard this file's edits before deleting it.")
                                } else {
                                    scope.launch {
                                        fileOperationBusy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                repository.deleteEntry(projectId, path)
                                            }
                                        }.onSuccess {
                                            if (editingProjectId == projectId && editingPath == path) {
                                                clearEditor()
                                            }
                                            refreshFiles(projectId)
                                            showMessage("Deleted $path.")
                                            entryPath = ""
                                            showDeleteEntry = false
                                        }.onFailure {
                                            if (it is CancellationException) throw it
                                            showMessage("Delete failed: ${it.message}")
                                        }
                                        fileOperationBusy = false
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            "Delete",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDeleteEntry = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ---------------------------------------------------------
        // UNSAVED CHANGES
        // ---------------------------------------------------------

        val changedFile = fileConflict
        if (changedFile != null &&
            changedFile.projectId == editingProjectId &&
            changedFile.path == editingPath
        ) {
            AlertDialog(
                onDismissRequest = {
                    if (!isLoadingFile) fileConflict = null
                },
                title = { Text("File changed on disk") },
                text = {
                    Text(
                        "${changedFile.path} changed after you opened it, likely through sync. " +
                            "Your editor draft is preserved. Choose whether to replace the latest file or reload it."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            fileConflict = null
                            saveFile(overwriteIfCurrentIs = changedFile.latestContent)
                        }
                    ) {
                        Text("Overwrite latest")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                isLoadingFile = true
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        repository.readFile(
                                            changedFile.projectId,
                                            changedFile.path
                                        )
                                    }
                                }.onSuccess { latest ->
                                    if (editingProjectId == changedFile.projectId &&
                                        editingPath == changedFile.path
                                    ) {
                                        editorBaseContent = latest
                                        editorContent = latest
                                        editorDirty = false
                                        draftStore.markDirty(
                                            changedFile.projectId,
                                            changedFile.path,
                                            false
                                        )
                                        fileConflict = null
                                        draftStore.delete(
                                            changedFile.projectId,
                                            changedFile.path
                                        )
                                        showMessage("Reloaded the latest file version.")
                                    }
                                }.onFailure {
                                    if (it is CancellationException) throw it
                                    showMessage("Could not reload the file: ${it.message}")
                                }
                                isLoadingFile = false
                            }
                        }
                    ) {
                        Text("Reload latest")
                    }
                }
            )
        }

        val conflictingDraft = draftConflict
        if (conflictingDraft != null &&
            conflictingDraft.projectId == editingProjectId &&
            conflictingDraft.path == editingPath
        ) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Recovered draft needs review") },
                text = {
                    Text(
                        "${conflictingDraft.path} changed after this draft was saved. " +
                            "Recovering it will replace the current file when you save."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            editorContent = conflictingDraft.content
                            editorDirty = editorContent != editorBaseContent
                            draftStore.markDirty(
                                conflictingDraft.projectId,
                                conflictingDraft.path,
                                editorDirty
                            )
                            draftConflict = null
                        }
                    ) {
                        Text("Recover draft")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                draftStore.delete(
                                    conflictingDraft.projectId,
                                    conflictingDraft.path
                                )
                            }
                            editorContent = editorBaseContent
                            editorDirty = false
                            draftStore.markDirty(
                                conflictingDraft.projectId,
                                conflictingDraft.path,
                                false
                            )
                            draftConflict = null
                        }
                    ) {
                        Text("Discard draft")
                    }
                }
            )
        }

        if (showDiscardChanges) {
            AlertDialog(
                onDismissRequest = {
                    showDiscardChanges = false
                    pendingOpenPath = null
                    pendingProject = null
                },
                title = {
                    Text("Unsaved changes")
                },
                text = {
                    Text(
                        "The current file has unsaved changes. " +
                        "Discard them and continue?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val targetProject = pendingProject
                            val targetPath = pendingOpenPath
                            val destination = pendingTab

                            showDiscardChanges = false
                            pendingProject = null
                            pendingOpenPath = null

                            clearEditor()

                            if (targetProject != null) {
                                selected = targetProject
                                selectedProjectId = targetProject.id
                                refreshFiles(targetProject.id)
                                tab = destination
                            } else if (!targetPath.isNullOrEmpty()) {
                                openFile(targetPath)
                            }
                        }
                    ) {
                        Text(
                            "Discard",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDiscardChanges = false
                            pendingOpenPath = null
                            pendingProject = null
                        }
                    ) {
                        Text("Keep editing")
                    }
                }
            )
        }
    }
}

// =============================================================
// REUSABLE CLIENT-FACING UI COMPONENTS
// =============================================================

@Composable
private fun ClientPageIntro(
    eyebrow: String,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClientSectionHeading(
    title: String,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.weight(1f))

        Text(
            detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClientInfoCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                content()
            }
        )
    }
}

@Composable
private fun ClientEmptyState(
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    selected: Boolean,
    onOpen: () -> Unit,
    onBuild: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    project.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (selected) {
                    Text(
                        "SELECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                project.rootPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                if (project.hasGradleWrapper) {
                    "Gradle wrapper detected"
                } else {
                    "No Gradle wrapper detected"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Open project")
                }

                OutlinedButton(
                    onClick = onBuild,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Build")
                }
            }
        }
    }
}

@Composable
private fun FileEntryCard(
    path: String,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(path) {
        mutableStateOf(false)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    path.substringBeforeLast("/", "Project root"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(
                onClick = onOpen
            ) {
                Text(if (active) "Editing" else "Open")
            }

            Box {
                IconButton(
                    onClick = {
                        menuExpanded = true
                    }
                ) {
                    Text("⋮")
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                    }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("Rename")
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorPanel(
    path: String,
    content: String,
    dirty: Boolean,
    loading: Boolean,
    saving: Boolean,
    draftStatus: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "SOURCE EDITOR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        path,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    if (dirty) "Unsaved" else "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }

            Text(
                draftStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                enabled = !loading && !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp),
                minLines = 12,
                maxLines = 30,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text("Source code")
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    enabled = dirty && !loading && !saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saving) "Saving…" else "Save changes")
                }

                OutlinedButton(
                    onClick = onClose,
                    enabled = !loading && !saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close editor")
                }
            }
        }
    }
}

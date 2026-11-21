
package com.twinpower.andro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

private fun runWasCreatedAfterDispatch(
    createdAt: String,
    dispatchedAt: String
): Boolean =
    runCatching {
        !Instant.parse(createdAt).isBefore(Instant.parse(dispatchedAt))
    }.getOrDefault(false)

private fun buildFailureSummary(run: GitHubWorkflowRun): String =
    when (run.conclusion) {
        "failure" -> "The workflow failed."
        "cancelled" -> "The workflow was cancelled."
        "timed_out" -> "The workflow exceeded its time limit."
        "action_required" -> "GitHub requires an action before this workflow can continue."
        "startup_failure" -> "GitHub could not start the workflow."
        "stale" -> "GitHub marked this workflow run as stale."
        "skipped" -> "The workflow was skipped by its conditions."
        "neutral" -> "The workflow finished without a success or failure result."
        else -> "The workflow completed with ${run.conclusion ?: "an unknown result"}."
    }

private fun remoteActionError(action: String, error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        error is UnknownHostException ->
            "Can't reach GitHub to $action. Check your connection and try again."
        error is SocketTimeoutException ->
            "GitHub took too long to $action. Try again in a moment."
        message.contains("GitHub API 401") ->
            "GitHub authorization failed. Reconnect GitHub and try again."
        message.contains("GitHub API 403") ->
            "GitHub denied access. Check repository access and Actions permissions."
        message.contains("GitHub API 404") ->
            "GitHub could not find the repository, workflow, branch, or run. Check the saved settings."
        message.contains("GitHub API 429") ->
            "GitHub is limiting requests. Wait briefly, then try again."
        Regex("GitHub API \\d{3}:").containsMatchIn(message) ->
            "GitHub returned an error while trying to $action. Check the repository settings and retry."
        message.isNotBlank() && !message.contains("Exception") &&
            !message.contains("java.") -> message
        else -> "Could not $action. Check your connection and try again."
    }
}

@Composable
fun GitHubRemoteBuilds(repository: ProjectRepository) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var projects by remember {
        mutableStateOf(emptyList<ProjectInfo>())
    }

    var projectsLoading by remember { mutableStateOf(true) }
    var projectsLoadError by remember { mutableStateOf<String?>(null) }
    var projectReloadRequest by remember { mutableIntStateOf(0) }

    val bindingStore = remember(context) {
        ProjectGitHubBindingStore(context)
    }

    var selectedProjectId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var selectedProject by remember {
        mutableStateOf<ProjectInfo?>(null)
    }
    var bindingLoadedProjectId by remember { mutableStateOf<String?>(null) }
    var restoredRunsForProject by remember { mutableStateOf<String?>(null) }

    var selectedRunId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var trackedRepository by rememberSaveable { mutableStateOf("") }
    var trackedBranch by rememberSaveable { mutableStateOf("") }
    var trackedWorkflowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var trackedDispatchAt by rememberSaveable { mutableStateOf("") }

    var projectMenu by remember {
        mutableStateOf(false)
    }

    var owner by rememberSaveable { mutableStateOf("") }
    var repositoryName by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("main") }
    var commitMessage by rememberSaveable {
        mutableStateOf("Build Android project with Andro")
    }

    var workflows by remember {
        mutableStateOf<List<GitHubWorkflow>>(emptyList())
    }

    var selectedWorkflow by remember {
        mutableStateOf<GitHubWorkflow?>(null)
    }
    var selectedWorkflowId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }

    var workflowMenu by remember {
        mutableStateOf(false)
    }

    var runs by remember {
        mutableStateOf<List<GitHubWorkflowRun>>(emptyList())
    }

    var selectedRun by remember {
        mutableStateOf<GitHubWorkflowRun?>(null)
    }

    var artifacts by remember {
        mutableStateOf<List<GitHubArtifact>>(emptyList())
    }

    var loading by remember { mutableStateOf(false) }

    var status by remember {
        mutableStateOf(
            "Connect GitHub in the GitHub Integration section first."
        )
    }

    fun clearRunTracking() {
        selectedRun = null
        selectedRunId = null
        artifacts = emptyList()
        trackedRepository = ""
        trackedBranch = ""
        trackedWorkflowId = null
        trackedDispatchAt = ""
    }

    val tokenProvider = remember(context) {
        {
            GitHubSecureStore.readToken(context)
        }
    }

    val sync = remember(repository, context) {
        GitHubProjectSync(
            repository = repository,
            tokenProvider = tokenProvider,
            editorDraftStore = EditorDraftStore(context)
        )
    }

    val workflowManager = remember(context) {
        GitHubWorkflowManager(tokenProvider)
    }

    val orchestrator = remember(sync, workflowManager) {
        GitHubBuildOrchestrator(sync, workflowManager)
    }

    /**
     * Restore the saved GitHub repository association whenever
     * the selected local project changes.
     */
    LaunchedEffect(repository, projectReloadRequest) {
        projectsLoading = true
        projectsLoadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.listProjects()
            }
        }.onSuccess { loaded ->
            projects = loaded
            selectedProject = loaded.firstOrNull { it.id == selectedProjectId }
                ?: loaded.firstOrNull()
            selectedProjectId = selectedProject?.id
        }.onFailure {
            if (it is CancellationException) throw it
            projectsLoadError = remoteActionError("load local projects", it)
            status = projectsLoadError.orEmpty()
        }
        projectsLoading = false
    }

    LaunchedEffect(selectedProject?.id) {
        val project = selectedProject
        bindingLoadedProjectId = null

        workflows = emptyList()
        selectedWorkflow = null
        selectedWorkflowId = null
        runs = emptyList()
        selectedRun = null
        artifacts = emptyList()

        if (project == null) {
            owner = ""
            repositoryName = ""
            branch = "main"
            bindingLoadedProjectId = null
            return@LaunchedEffect
        }

        val savedBinding = try {
            withContext(Dispatchers.IO) {
                bindingStore.get(project.id)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status = remoteActionError("load saved GitHub settings", error)
            bindingLoadedProjectId = project.id
            return@LaunchedEffect
        }

        if (savedBinding != null) {
            owner = savedBinding.owner
            repositoryName = savedBinding.repository
            branch = savedBinding.branch
            selectedWorkflowId = savedBinding.workflowId

            status =
                "Restored GitHub settings for ${project.name}. " +
                "Load workflows to restore the saved workflow selection."
        } else {
            owner = ""
            repositoryName = ""
            branch = "main"
            selectedWorkflowId = null

            status =
                "No GitHub repository is saved for ${project.name}."
        }
        bindingLoadedProjectId = project.id
    }

    fun repositoryParts(): Pair<String, String>? {
        val cleanOwner = owner.trim()
        val cleanRepository = repositoryName.trim()

        if (
            !cleanOwner.matches(Regex("[A-Za-z0-9_.-]{1,100}")) ||
            !cleanRepository.matches(Regex("[A-Za-z0-9_.-]{1,100}"))
        ) {
            status = "Enter a valid GitHub owner and repository name."
            return null
        }

        return cleanOwner to cleanRepository
    }

    fun saveRepositorySettings() {
        val project = selectedProject

        if (project == null) {
            status = "Select a local project first."
            return
        }

        val parts = repositoryParts() ?: return
        val workflow = selectedWorkflow

        if (workflow == null) {
            status = "Load workflows and select a workflow before saving settings."
            return
        }

        if (workflow.state != "active") {
            status = "The selected GitHub workflow is not active."
            return
        }

        val targetBranch = branch.trim()
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bindingStore.save(
                        ProjectGitHubBinding(
                            projectId = project.id,
                            owner = parts.first,
                            repository = parts.second,
                            branch = targetBranch,
                            workflowId = workflow.id
                        )
                    )
                }
            }.onSuccess {
                status = "GitHub settings saved for ${project.name}."
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("save GitHub settings", it)
            }
            loading = false
        }
    }

    fun loadWorkflows() {
        val parts = repositoryParts() ?: return
        val projectId = selectedProject?.id

        scope.launch {
            loading = true
            status = "Loading GitHub Actions workflows..."

            runCatching {
                withContext(Dispatchers.IO) {
                    workflowManager.listWorkflows(
                        parts.first,
                        parts.second
                    ) to projectId?.let(bindingStore::get)
                }
            }.onSuccess { (result, savedBinding) ->
                workflows = result

                selectedWorkflow = result.firstOrNull {
                    it.id == (selectedWorkflowId ?: savedBinding?.workflowId) &&
                        it.state == "active"
                } ?: result.firstOrNull {
                    it.state == "active"
                }
                selectedWorkflowId = selectedWorkflow?.id

                status = if (result.isEmpty()) {
                    "No workflows found. Add a workflow with workflow_dispatch."
                } else {
                    "${result.size} workflow(s) loaded."
                }
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("load workflows", it)
            }

            loading = false
        }
    }

    fun loadRuns() {
        val parts = repositoryParts() ?: return
        val targetBranch = branch.trim()

        scope.launch {
            loading = true
            status = "Loading recent build runs..."

            runCatching {
                withContext(Dispatchers.IO) {
                    workflowManager.listWorkflowRuns(
                        owner = parts.first,
                        repository = parts.second,
                        branch = targetBranch.ifBlank { null },
                        perPage = 20
                    )
                }
            }.onSuccess { result ->
                runs = result
                val trackedRun = result.firstOrNull { run ->
                    trackedRepository == "${parts.first}/${parts.second}" &&
                        trackedBranch == targetBranch &&
                        trackedWorkflowId == run.workflowId &&
                        trackedDispatchAt.isNotBlank() &&
                        runWasCreatedAfterDispatch(
                            run.createdAt,
                            trackedDispatchAt
                        )
                }
                val runToRestore = trackedRun
                    ?: result.firstOrNull { it.id == selectedRunId }
                if (runToRestore == null) {
                    selectedRun = null
                    artifacts = emptyList()
                    status = if (trackedDispatchAt.isNotBlank() &&
                        trackedRepository == "${parts.first}/${parts.second}"
                    ) {
                        "${result.size} recent runs loaded. The dispatched run may still be starting."
                    } else {
                        "${result.size} recent workflow runs loaded."
                    }
                } else {
                    selectedRun = runToRestore
                    selectedRunId = runToRestore.id
                    status = "Restoring build run ${runToRestore.id}..."
                    runCatching {
                        orchestrator.refreshStatus(
                            owner = parts.first,
                            repository = parts.second,
                            runId = runToRestore.id
                        )
                    }.onSuccess { build ->
                        selectedRun = build.run
                        artifacts = build.artifacts
                        status = when {
                            build.run.status != "completed" ->
                                "Build run ${build.run.id} is ${build.run.status}."
                            build.run.succeeded ->
                                "Build run ${build.run.id} succeeded. " +
                                    "${build.artifacts.size} artifact(s) available."
                            else ->
                                "${buildFailureSummary(build.run)} Run ${build.run.id}. " +
                                    "Open GitHub to inspect failed job logs."
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        status = "Run ${runToRestore.id} was found, but its latest status " +
                            "could not be loaded. " +
                            remoteActionError("refresh build status", error)
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("load workflow runs", it)
            }

            loading = false
        }
    }

    LaunchedEffect(
        bindingLoadedProjectId,
        selectedProject?.id,
        owner,
        repositoryName,
        branch,
        selectedRunId,
        trackedRepository,
        trackedBranch,
        trackedWorkflowId,
        trackedDispatchAt
    ) {
        val projectId = selectedProject?.id
        if (projectId == null || bindingLoadedProjectId != projectId ||
            restoredRunsForProject == projectId
        ) return@LaunchedEffect

        val repositoryKey = "${owner.trim()}/${repositoryName.trim()}"
        val hasRepository = owner.trim().matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
            repositoryName.trim().matches(Regex("[A-Za-z0-9_.-]{1,100}"))
        val trackedBuildMatchesRepository = trackedRepository == repositoryKey &&
            trackedBranch == branch.trim()
        val hasTrackedBuild = hasRepository &&
            (selectedRunId != null ||
                trackedDispatchAt.isNotBlank() && trackedBuildMatchesRepository)
        if (hasTrackedBuild) loadRuns()
        restoredRunsForProject = projectId
    }

    fun synchronize(pull: Boolean) {
        val project = selectedProject
        val parts = repositoryParts()
        val targetBranch = branch.trim()
        val targetCommitMessage = commitMessage

        if (project == null) {
            status = "Create or import a local Android project first."
            return
        }

        if (parts == null) return

        if (targetBranch.isBlank()) {
            status = "Enter a GitHub branch."
            return
        }

        scope.launch {
            loading = true

            status = if (pull) {
                "Pulling GitHub files into Andro..."
            } else {
                "Pushing Andro project files to GitHub..."
            }

            runCatching {
                if (pull) {
                    sync.pull(
                        project = project,
                        owner = parts.first,
                        repo = parts.second,
                        branch = targetBranch
                    )
                } else {
                    sync.push(
                        project = project,
                        owner = parts.first,
                        repo = parts.second,
                        branch = targetBranch,
                        commitMessage = targetCommitMessage
                    )
                }
            }.onSuccess {
                status = it.message
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("synchronize project files", it)
            }

            loading = false
        }
    }

    fun startRemoteBuild() {
        val project = selectedProject
        val parts = repositoryParts()
        val workflow = selectedWorkflow
        val targetBranch = branch.trim()
        val targetCommitMessage = commitMessage

        if (project == null) {
            status = "Select a local Android project first."
            return
        }

        if (parts == null) return

        if (workflow == null) {
            status = "Load workflows and select a workflow first."
            return
        }

        if (workflow.state != "active") {
            status = "The selected GitHub workflow is not active."
            return
        }

        if (targetBranch.isBlank()) {
            status = "Enter the target GitHub branch."
            return
        }

        scope.launch {
            loading = true
            selectedRun = null
            artifacts = emptyList()

            status = "Saving repository settings..."

            val bindingSaved = runCatching {
                withContext(Dispatchers.IO) {
                    bindingStore.save(
                        ProjectGitHubBinding(
                            projectId = project.id,
                            owner = parts.first,
                            repository = parts.second,
                            branch = targetBranch,
                            workflowId = workflow.id
                        )
                    )
                }
            }

            val bindingError = bindingSaved.exceptionOrNull()
            if (bindingError != null) {
                if (bindingError is CancellationException) throw bindingError
                status = remoteActionError("save GitHub settings", bindingError)
                loading = false
                return@launch
            }

            status = "Uploading project files and dispatching remote build..."

            runCatching {
                orchestrator.dispatch(
                    project = project,
                    request = RemoteBuildRequest(
                        owner = parts.first,
                        repository = parts.second,
                        branch = targetBranch,
                        workflowId = workflow.id,
                        commitMessage = targetCommitMessage
                    )
                )
            }.onSuccess { dispatch ->
                trackedRepository = dispatch.repository
                trackedBranch = dispatch.branch
                trackedWorkflowId = dispatch.workflowId
                trackedDispatchAt = dispatch.dispatchedAt
                selectedRun = null
                selectedRunId = null
                status = "Project synchronized (${dispatch.syncResult.filesProcessed} files). " +
                    "GitHub accepted the build; locating its run..."

                val lookup = runCatching {
                    orchestrator.findDispatchedRun(dispatch)
                }
                lookup.exceptionOrNull()?.let {
                    if (it is CancellationException) throw it
                }
                val matchingRun = lookup.getOrNull()
                if (matchingRun != null) {
                    selectedRun = matchingRun
                    selectedRunId = matchingRun.id
                    runs = (listOf(matchingRun) + runs.filterNot {
                        it.id == matchingRun.id
                    }).take(20)
                    status = "Build run ${matchingRun.id} is ${matchingRun.status}."
                } else {
                    status = "GitHub accepted the build, but its run is not visible yet. " +
                        "Refresh runs shortly."
                    loading = false
                    loadRuns()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                status = if (it is RemoteBuildDispatchException) {
                    "Project synchronized (${it.syncResult.filesProcessed} files), " +
                        remoteActionError("dispatch the workflow", it.cause ?: it)
                } else {
                    remoteActionError("start the remote build", it)
                }
            }

            loading = false
        }
    }

    fun refreshSelectedRun(run: GitHubWorkflowRun) {
        val parts = repositoryParts() ?: return

        scope.launch {
            loading = true
            status = "Refreshing build status..."

            runCatching {
                orchestrator.refreshStatus(
                    owner = parts.first,
                    repository = parts.second,
                    runId = run.id
                )
            }.onSuccess { result ->
                selectedRun = result.run
                selectedRunId = result.run.id
                artifacts = result.artifacts

                status = when {
                    result.run.status != "completed" ->
                        "Build status: ${result.run.status}"

                    result.run.conclusion == "success" ->
                        "Build succeeded. ${result.artifacts.size} artifact(s) available."

                    else ->
                        "${buildFailureSummary(result.run)} Run ${result.run.id}. " +
                            "Open GitHub to inspect failed job logs."
                }
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("refresh build status", it)
            }

            loading = false
        }
    }

    fun downloadArtifact(artifact: GitHubArtifact) {
        val project = selectedProject
        val parts = repositoryParts()

        if (project == null) {
            status = "Select a local project before downloading an artifact."
            return
        }

        if (parts == null) return

        if (artifact.expired) {
            status = "This GitHub artifact has expired and cannot be downloaded."
            return
        }

        scope.launch {
            loading = true
            status = "Downloading ${artifact.name} from GitHub..."

            runCatching {
                orchestrator.downloadArtifact(
                    project = project,
                    owner = parts.first,
                    repository = parts.second,
                    artifact = artifact
                )
            }.onSuccess { downloadedFile ->
                status =
                    "${artifact.name} downloaded and extracted. " +
                    "Project location: ${downloadedFile.absolutePath}"
            }.onFailure {
                if (it is CancellationException) throw it
                status = remoteActionError("download the artifact", it)
            }

            loading = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                tokenProvider() != null
            }
        }.onSuccess { connected ->
            if (!connected) {
                status = "Connect GitHub above before using remote builds."
            }
        }.onFailure {
            if (it is CancellationException) throw it
            status = remoteActionError("check the GitHub connection", it)
        }
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "GITHUB PROJECT SYNC & REMOTE BUILDS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Synchronize a local Andro project with a GitHub repository, " +
                    "then use GitHub Actions to build the Android project remotely."
            )

            HorizontalDivider()

            Text(
                "LOCAL PROJECT",
                fontWeight = FontWeight.SemiBold
            )

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { projectMenu = true },
                enabled = !loading && projects.isNotEmpty()
            ) {
                Text(selectedProject?.name ?: "Select local project")
            }

            DropdownMenu(
                expanded = projectMenu,
                onDismissRequest = { projectMenu = false }
            ) {
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            selectedProject = project
                            selectedProjectId = project.id
                            projectMenu = false
                            clearRunTracking()
                        }
                    )
                }
            }

            if (projectsLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (projectsLoadError != null) {
                Text(
                    projectsLoadError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    onClick = { projectReloadRequest++ }
                ) {
                    Text("Retry loading projects")
                }
            } else if (projects.isEmpty()) {
                Text(
                    "Create or import a project in Workspace before synchronizing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "GITHUB REPOSITORY",
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = owner,
                onValueChange = {
                    owner = it
                    clearRunTracking()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub owner") },
                placeholder = { Text("your-username") },
                singleLine = true,
                enabled = !loading
            )

            OutlinedTextField(
                value = repositoryName,
                onValueChange = {
                    repositoryName = it
                    clearRunTracking()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Repository name") },
                placeholder = { Text("android-project") },
                singleLine = true,
                enabled = !loading
            )

            Text(
                "Enter the repository owner and name separately. Andro pushes the selected " +
                    "project to the branch before dispatching a build.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = branch,
                onValueChange = {
                    branch = it
                    clearRunTracking()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Branch") },
                singleLine = true,
                enabled = !loading
            )

            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Commit message") },
                enabled = !loading
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                    onClick = { synchronize(pull = true) }
                ) {
                    Text("Pull")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                    onClick = { synchronize(pull = false) }
                ) {
                    Text("Push")
                }
            }

            Text(
                "Pull overwrites matching local files. It does not delete local files " +
                    "that are absent from GitHub. Push uploads the project files and " +
                    "creates a single GitHub commit.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text(
                "REMOTE ANDROID BUILD",
                fontWeight = FontWeight.SemiBold
            )

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = { loadWorkflows() }
            ) {
                Text("Load GitHub Actions workflows")
            }

            Text(
                "Choose an active workflow that supports workflow_dispatch. The GitHub " +
                    "connection needs repository contents write and Actions write access.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && workflows.isNotEmpty(),
                onClick = { workflowMenu = true }
            ) {
                Text(
                    selectedWorkflow?.let {
                        "${it.name} · ${it.id}"
                    } ?: "Select workflow"
                )
            }

            DropdownMenu(
                expanded = workflowMenu,
                onDismissRequest = { workflowMenu = false }
            ) {
                workflows.forEach { workflow ->
                    DropdownMenuItem(
                        text = {
                            Text("${workflow.name} (${workflow.state})")
                        },
                        onClick = {
                            selectedWorkflow = workflow
                            selectedWorkflowId = workflow.id
                            workflowMenu = false
                        }
                    )
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && selectedWorkflow != null,
                onClick = { saveRepositorySettings() }
            ) {
                Text("Save repository settings")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && selectedWorkflow != null,
                onClick = { startRemoteBuild() }
            ) {
                Text("Push project and start remote build")
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                    onClick = { loadRuns() }
                ) {
                    Text("Refresh runs")
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !loading && selectedRun != null,
                    onClick = {
                        selectedRun?.let {
                            refreshSelectedRun(it)
                        }
                    }
                ) {
                    Text("Refresh status")
                }
            }

            runs.forEach { run ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            run.name.ifBlank { "GitHub Actions run" },
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            "Run ${run.id} · ${run.branch}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            if (run.status == "completed") {
                                "Conclusion: ${run.conclusion ?: "unknown"}"
                            } else {
                                "Status: ${run.status}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                selectedRun = run
                                selectedRunId = run.id
                                refreshSelectedRun(run)
                            }
                        ) {
                            Text("Inspect run")
                        }

                        if (run.status == "completed" && !run.succeeded) {
                            Text(
                                buildFailureSummary(run),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (run.htmlUrl.startsWith("https://github.com/")) {
                                OutlinedButton(
                                    enabled = !loading,
                                    onClick = { uriHandler.openUri(run.htmlUrl) }
                                ) {
                                    Text("Open GitHub job logs")
                                }
                            }
                        }
                    }
                }
            }

            if (artifacts.isNotEmpty()) {
                HorizontalDivider()

                Text(
                    "BUILD ARTIFACTS",
                    fontWeight = FontWeight.Bold
                )

                artifacts.forEach { artifact ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                artifact.name,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                "${artifact.sizeBytes / 1024} KB" +
                                    if (artifact.expired) " · Expired" else "",
                                style = MaterialTheme.typography.bodySmall
                            )

                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading &&
                                    !artifact.expired &&
                                    selectedProject != null,
                                onClick = {
                                    downloadArtifact(artifact)
                                }
                            ) {
                                Text(
                                    if (artifact.expired) {
                                        "Artifact expired"
                                    } else {
                                        "Download into project"
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    "Downloaded artifacts are extracted under the selected project's " +
                        "build/andro-artifacts directory.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

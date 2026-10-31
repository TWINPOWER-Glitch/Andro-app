
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitHubRemoteBuilds(repository: ProjectRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val projects = remember {
        repository.listProjects()
    }

    var selectedProject by remember {
        mutableStateOf(projects.firstOrNull())
    }

    var projectMenu by remember {
        mutableStateOf(false)
    }

    var owner by remember { mutableStateOf("") }
    var repositoryName by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var commitMessage by remember {
        mutableStateOf("Build Android project with Andro")
    }

    var workflows by remember {
        mutableStateOf<List<GitHubWorkflow>>(emptyList())
    }

    var selectedWorkflow by remember {
        mutableStateOf<GitHubWorkflow?>(null)
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

    val tokenProvider = remember(context) {
        {
            GitHubSecureStore.readToken(context)
        }
    }

    val sync = remember(repository, context) {
        GitHubProjectSync(repository, tokenProvider)
    }

    val workflowManager = remember(context) {
        GitHubWorkflowManager(tokenProvider)
    }

    val orchestrator = remember(sync, workflowManager) {
        GitHubBuildOrchestrator(sync, workflowManager)
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

    fun loadWorkflows() {
        val parts = repositoryParts() ?: return

        scope.launch {
            loading = true
            status = "Loading GitHub Actions workflows..."

            runCatching {
                withContext(Dispatchers.IO) {
                    workflowManager.listWorkflows(
                        parts.first,
                        parts.second
                    )
                }
            }.onSuccess { result ->
                workflows = result

                selectedWorkflow = result.firstOrNull {
                    it.state == "active"
                }

                status = if (result.isEmpty()) {
                    "No workflows found. Add a workflow with workflow_dispatch."
                } else {
                    "${result.size} workflow(s) loaded."
                }
            }.onFailure {
                status = it.message ?: "Could not load workflows."
            }

            loading = false
        }
    }

    fun loadRuns() {
        val parts = repositoryParts() ?: return

        scope.launch {
            loading = true
            status = "Loading recent build runs..."

            runCatching {
                withContext(Dispatchers.IO) {
                    workflowManager.listWorkflowRuns(
                        owner = parts.first,
                        repository = parts.second,
                        branch = branch.trim().ifBlank { null },
                        perPage = 20
                    )
                }
            }.onSuccess { result ->
                runs = result
                status = "${result.size} recent workflow run(s) loaded."
            }.onFailure {
                status = it.message ?: "Could not load workflow runs."
            }

            loading = false
        }
    }

    fun synchronize(pull: Boolean) {
        val project = selectedProject
        val parts = repositoryParts()

        if (project == null) {
            status = "Create or import a local Android project first."
            return
        }

        if (parts == null) return

        if (branch.isBlank()) {
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
                        branch = branch.trim()
                    )
                } else {
                    sync.push(
                        project = project,
                        owner = parts.first,
                        repo = parts.second,
                        branch = branch.trim(),
                        commitMessage = commitMessage
                    )
                }
            }.onSuccess {
                status = it.message
            }.onFailure {
                status = it.message ?: "GitHub synchronization failed."
            }

            loading = false
        }
    }

    fun startRemoteBuild() {
        val project = selectedProject
        val parts = repositoryParts()
        val workflow = selectedWorkflow

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

        if (branch.isBlank()) {
            status = "Enter the target GitHub branch."
            return
        }

        scope.launch {
            loading = true
            selectedRun = null
            artifacts = emptyList()
            status = "Uploading project files and dispatching remote build..."

            runCatching {
                orchestrator.dispatch(
                    project = project,
                    request = RemoteBuildRequest(
                        owner = parts.first,
                        repository = parts.second,
                        branch = branch.trim(),
                        workflowId = workflow.id,
                        commitMessage = commitMessage
                    )
                )
            }.onSuccess { dispatch ->
                status =
                    "Project synchronized (${dispatch.syncResult.filesProcessed} files). " +
                    "Build dispatch accepted by GitHub. Refresh runs to find its status."

                loadRuns()
            }.onFailure {
                status = it.message ?: "Remote build dispatch failed."
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
                artifacts = result.artifacts

                status = when {
                    result.run.status != "completed" ->
                        "Build status: ${result.run.status}"

                    result.run.conclusion == "success" ->
                        "Build succeeded. ${result.artifacts.size} artifact(s) available."

                    else ->
                        "Build completed: ${result.run.conclusion ?: "unknown"}"
                }
            }.onFailure {
                status = it.message ?: "Could not refresh build status."
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
                    "Artifact downloaded and extracted successfully. " +
                    "Project location: ${downloadedFile.absolutePath}"
            }.onFailure {
                status = it.message ?: "Artifact download failed."
            }

            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (tokenProvider() == null) {
            status = "Connect GitHub above before using remote builds."
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
                enabled = projects.isNotEmpty()
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
                            projectMenu = false
                            selectedRun = null
                            artifacts = emptyList()
                        }
                    )
                }
            }

            if (projects.isEmpty()) {
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
                onValueChange = { owner = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub owner") },
                placeholder = { Text("your-username") },
                singleLine = true
            )

            OutlinedTextField(
                value = repositoryName,
                onValueChange = { repositoryName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Repository name") },
                placeholder = { Text("android-project") },
                singleLine = true
            )

            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Branch") },
                singleLine = true
            )

            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Commit message") }
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

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = workflows.isNotEmpty(),
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
                            workflowMenu = false
                        }
                    )
                }
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
                                refreshSelectedRun(run)
                            }
                        ) {
                            Text("Inspect run")
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


package com.twinpower.andro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

data class RemoteBuildRequest(
    val owner: String,
    val repository: String,
    val branch: String,
    val workflowId: Long,
    val commitMessage: String = "Build Android project with Andro"
)

data class RemoteBuildDispatch(
    val repository: String,
    val branch: String,
    val workflowId: Long,
    val dispatchedAt: String,
    val syncResult: GitHubSyncResult
)

data class RemoteBuildStatus(
    val run: GitHubWorkflowRun,
    val artifacts: List<GitHubArtifact>
) {
    val completed: Boolean
        get() = run.isFinished

    val successful: Boolean
        get() = run.succeeded
}

class GitHubBuildOrchestrator(
    private val sync: GitHubProjectSync,
    private val workflows: GitHubWorkflowManager
) {

    /**
     * Pushes the selected local project and dispatches its GitHub workflow.
     *
     * The workflow must already exist in the target repository and support
     * workflow_dispatch. The GitHub token needs contents write and actions
     * write permissions.
     */
    suspend fun dispatch(
        project: ProjectInfo,
        request: RemoteBuildRequest
    ): RemoteBuildDispatch = withContext(Dispatchers.IO) {
        require(request.owner.isNotBlank()) {
            "GitHub owner is required."
        }
        require(request.repository.isNotBlank()) {
            "GitHub repository is required."
        }
        require(request.branch.isNotBlank()) {
            "GitHub branch is required."
        }
        require(request.workflowId > 0) {
            "A valid workflow ID is required."
        }

        val startedAt = Instant.now().toString()

        val syncResult = sync.push(
            project = project,
            owner = request.owner,
            repo = request.repository,
            branch = request.branch,
            commitMessage = request.commitMessage
        )

        workflows.dispatchWorkflow(
            owner = request.owner,
            repository = request.repository,
            workflowId = request.workflowId.toString(),
            branch = request.branch
        )

        RemoteBuildDispatch(
            repository = "${request.owner}/${request.repository}",
            branch = request.branch,
            workflowId = request.workflowId,
            dispatchedAt = startedAt,
            syncResult = syncResult
        )
    }

    /**
     * Finds the run created by a dispatch.
     *
     * GitHub's workflow_dispatch endpoint returns HTTP 204 rather than a run
     * ID, so Andro locates the new run using its workflow, branch and
     * dispatch timestamp.
     */
    suspend fun findDispatchedRun(
        dispatch: RemoteBuildDispatch
    ): GitHubWorkflowRun = withContext(Dispatchers.IO) {
        val parts = dispatch.repository.split("/", limit = 2)

        if (parts.size != 2) {
            throw IOException("Invalid repository reference.")
        }

        val owner = parts[0]
        val repository = parts[1]
        val dispatchTime = Instant.parse(dispatch.dispatchedAt)

        val runs = workflows.listWorkflowRuns(
            owner = owner,
            repository = repository,
            branch = dispatch.branch,
            perPage = 30
        )

        runs.firstOrNull { run ->
            run.workflowId == dispatch.workflowId &&
                run.branch == dispatch.branch &&
                !Instant.parse(run.createdAt).isBefore(dispatchTime)
        } ?: throw IOException(
            "GitHub accepted the dispatch, but the workflow run " +
                "has not appeared yet. Refresh the run list shortly."
        )
    }

    /**
     * Reads the current status and artifacts for a known workflow run.
     */
    suspend fun refreshStatus(
        owner: String,
        repository: String,
        runId: Long
    ): RemoteBuildStatus = withContext(Dispatchers.IO) {
        require(runId > 0) {
            "Invalid workflow run ID."
        }

        val run = workflows.getWorkflowRun(
            owner = owner,
            repository = repository,
            runId = runId
        )

        val artifacts = if (run.isFinished && run.succeeded) {
            workflows.listArtifacts(
                owner = owner,
                repository = repository,
                runId = runId
            )
        } else {
            emptyList()
        }

        RemoteBuildStatus(
            run = run,
            artifacts = artifacts
        )
    }

    /**
     * Cancels a running GitHub Actions workflow.
     */
    suspend fun cancel(
        owner: String,
        repository: String,
        runId: Long
    ) = withContext(Dispatchers.IO) {
        workflows.cancelWorkflowRun(
            owner = owner,
            repository = repository,
            runId = runId
        )
    }

    /**
     * Re-runs all jobs in an existing workflow run.
     */
    suspend fun rerun(
        owner: String,
        repository: String,
        runId: Long
    ) = withContext(Dispatchers.IO) {
        workflows.rerunWorkflow(
            owner = owner,
            repository = repository,
            runId = runId
        )
    }
}


package com.twinpower.andro

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Secure bridge between Andro MCP build requests
 * and the GitHub Actions build orchestrator.
 *
 * Access tokens are never returned to MCP clients.
 */
class GitHubMcpBuildService(
    context: Context,
    private val repository: ProjectRepository
) {

    private val applicationContext =
        context.applicationContext

    private val bindingStore =
        ProjectGitHubBindingStore(applicationContext)

    private val tokenProvider: () -> String? = {
        GitHubSecureStore.readToken(applicationContext)
    }

    private fun createOrchestrator(
        token: String
    ): GitHubBuildOrchestrator {
        val authenticatedTokenProvider: () -> String? = {
            token
        }

        val sync = GitHubProjectSync(
            repository = repository,
            tokenProvider = authenticatedTokenProvider
        )

        val workflows = GitHubWorkflowManager(
            tokenProvider = authenticatedTokenProvider
        )

        return GitHubBuildOrchestrator(
            sync = sync,
            workflows = workflows
        )
    }

    /**
     * Dispatches a remote build for a project that already
     * has a saved GitHub repository configuration.
     */
    suspend fun buildProject(
        projectId: String,
        commitMessage: String = "Build Android project with Andro"
    ): RemoteBuildDispatch {

        require(projectId.isNotBlank()) {
            "Project ID is required."
        }

        require(commitMessage.isNotBlank()) {
            "Build commit message cannot be empty."
        }

        require(commitMessage.length <= 200) {
            "Build commit message is too long."
        }

        // Resolve the project from Andro's private project storage.
        val project = repository.getProject(projectId)

        // Verify that the resolved project is inside the managed
        // project directory and that its metadata matches its ID.
        val managedDirectory =
            repository.projectDir(projectId).canonicalFile

        val projectDirectory =
            java.io.File(project.rootPath).canonicalFile

        require(projectDirectory == managedDirectory) {
            "Project directory does not match its managed project ID."
        }

        require(project.id == projectId) {
            "Project metadata does not match the requested project."
        }

        // A project must have an explicit saved repository binding.
        val binding = bindingStore.get(projectId)
            ?: throw IOException(
                "This project has no saved GitHub repository configuration. " +
                    "Open Builds and save its repository settings first."
            )

        // Do not dispatch builds without an authenticated GitHub token.
        val token = tokenProvider()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "GitHub is not connected. Authenticate with GitHub before requesting a remote build."
            )

        // The orchestrator performs the project push and workflow dispatch.
        return createOrchestrator(token).dispatch(
            project = project,
            request = RemoteBuildRequest(
                owner = binding.owner,
                repository = binding.repository,
                branch = binding.branch,
                workflowId = binding.workflowId,
                commitMessage = commitMessage.trim()
            )
        )
    }

    /**
     * Finds the GitHub Actions run created by a previous dispatch.
     *
     * GitHub's workflow_dispatch endpoint does not return a run ID.
     * This method therefore searches the project's bound repository,
     * checking the workflow, branch and dispatch timestamp.
     *
     * The lookup is bounded to approximately 30 seconds.
     */
    suspend fun findDispatchedRun(
        projectId: String,
        dispatch: RemoteBuildDispatch
    ): GitHubWorkflowRun {

        require(projectId.isNotBlank()) {
            "Project ID is required."
        }

        // Confirm that the project still exists in Andro's workspace.
        repository.getProject(projectId)

        // Resolve the repository exclusively from the saved binding.
        val binding = bindingStore.get(projectId)
            ?: throw IOException(
                "This project has no saved GitHub repository configuration."
            )

        // Do not allow a dispatch result for another repository,
        // branch or workflow to be used with this project.
        val expectedRepository =
            "${binding.owner}/${binding.repository}"

        require(dispatch.repository.equals(
            expectedRepository,
            ignoreCase = true
        )) {
            "The dispatched repository does not match this project's saved GitHub binding."
        }

        require(dispatch.branch == binding.branch) {
            "The dispatched branch does not match this project's saved GitHub binding."
        }

        require(dispatch.workflowId == binding.workflowId) {
            "The dispatched workflow does not match this project's saved GitHub binding."
        }

        require(dispatch.dispatchedAt.isNotBlank()) {
            "The dispatch timestamp is missing."
        }

        // Use the existing secure GitHub token store.
        val token = tokenProvider()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "GitHub is not connected. Authenticate before looking up a workflow run."
            )

        val orchestrator = createOrchestrator(token)
        var lastLookupError: IOException? = null

        // Fifteen checks with two-second intervals provide a
        // bounded lookup period of approximately thirty seconds.
        repeat(15) { attempt ->
            try {
                return orchestrator.findDispatchedRun(dispatch)
            } catch (error: IOException) {
                lastLookupError = error

                // Do not delay after the final attempt.
                if (attempt < 14) {
                    delay(2_000)
                }
            }
        }

        throw IOException(
            "GitHub accepted the workflow dispatch, but its run ID " +
                "could not be located within 30 seconds. The workflow " +
                "may still appear shortly; refresh the GitHub Actions run list.",
            lastLookupError
        )
    }

    /**
     * Retrieves the actual GitHub Actions status for a run.
     *
     * The repository is resolved from the project's saved binding.
     * The caller cannot supply an arbitrary repository.
     *
     * Successful completed runs also return their available artifacts.
     */
    suspend fun getRemoteBuildStatus(
        projectId: String,
        runId: Long
    ): RemoteBuildStatus {

        require(projectId.isNotBlank()) {
            "Project ID is required."
        }

        require(runId > 0) {
            "A valid GitHub Actions run ID is required."
        }

        // Confirm that this project exists in Andro's workspace.
        repository.getProject(projectId)

        // Resolve the repository exclusively from the saved binding.
        val binding = bindingStore.get(projectId)
            ?: throw IOException(
                "This project has no saved GitHub repository configuration."
            )

        // Use the existing secure GitHub token store.
        val token = tokenProvider()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "GitHub is not connected. Authenticate before checking remote build status."
            )

        // Query GitHub Actions for the real run status.
        return createOrchestrator(token).refreshStatus(
            owner = binding.owner,
            repository = binding.repository,
            runId = runId
        )
    }

    /**
     * Returns the saved GitHub association for a project.
     *
     * No access token or credential information is exposed.
     */
    fun getProjectBinding(
        projectId: String
    ): ProjectGitHubBinding? {
        repository.getProject(projectId)
        return bindingStore.get(projectId)
    }

    /**
     * Checks whether the project has both:
     * - A saved GitHub repository configuration.
     * - An available GitHub access token.
     *
     * This does not dispatch a build or modify project files.
     */
    fun canBuildProject(
        projectId: String
    ): Boolean {
        return runCatching {
            repository.getProject(projectId)

            val binding = bindingStore.get(projectId)
                ?: return false

            binding.workflowId > 0 &&
                binding.owner.isNotBlank() &&
                binding.repository.isNotBlank() &&
                binding.branch.isNotBlank() &&
                !tokenProvider().isNullOrBlank()
        }.getOrDefault(false)
    }
}

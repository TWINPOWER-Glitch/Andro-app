package com.twinpower.andro

import android.content.Context
import java.io.IOException

/**
 * Secure bridge between Andro MCP build requests
 * and the GitHub Actions build orchestrator.
 *
 * The service:
 * 1. Resolves the requested local project.
 * 2. Loads that project's saved GitHub repository binding.
 * 3. Obtains the existing GitHub access token.
 * 4. Pushes the project and dispatches its configured workflow.
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

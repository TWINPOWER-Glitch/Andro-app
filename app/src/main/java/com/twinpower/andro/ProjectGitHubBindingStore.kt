
package com.twinpower.andro

import android.content.Context

/**
 * Persistent association between a local Andro project
 * and its remote GitHub repository.
 *
 * GitHub access tokens are deliberately not stored here.
 * They remain managed by GitHubSecureStore.
 */
data class ProjectGitHubBinding(
    val projectId: String,
    val owner: String,
    val repository: String,
    val branch: String,
    val workflowId: Long
)

class ProjectGitHubBindingStore(context: Context) {

    companion object {
        private const val PREFS_NAME =
            "andro_project_github_bindings"

        private const val KEY_OWNER = "owner"
        private const val KEY_REPOSITORY = "repository"
        private const val KEY_BRANCH = "branch"
        private const val KEY_WORKFLOW_ID = "workflow_id"

        private val PROJECT_ID_PATTERN =
            Regex("[A-Za-z0-9-]{1,64}")

        private val REPOSITORY_PART_PATTERN =
            Regex("[A-Za-z0-9_.-]{1,100}")
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * Retrieves the saved GitHub repository association
     * for a local project.
     */
    fun get(projectId: String): ProjectGitHubBinding? {
        validateProjectId(projectId)

        val prefix = keyPrefix(projectId)

        val owner = preferences.getString(
            prefix + KEY_OWNER,
            null
        ) ?: return null

        val repository = preferences.getString(
            prefix + KEY_REPOSITORY,
            null
        ) ?: return null

        val branch = preferences.getString(
            prefix + KEY_BRANCH,
            null
        ) ?: return null

        val workflowId = preferences.getLong(
            prefix + KEY_WORKFLOW_ID,
            -1L
        )

        return runCatching {
            validateBinding(
                owner = owner,
                repository = repository,
                branch = branch,
                workflowId = workflowId
            )

            ProjectGitHubBinding(
                projectId = projectId,
                owner = owner,
                repository = repository,
                branch = branch,
                workflowId = workflowId
            )
        }.getOrNull()
    }

    /**
     * Saves or replaces a project's GitHub association.
     */
    fun save(binding: ProjectGitHubBinding) {
        validateProjectId(binding.projectId)

        validateBinding(
            owner = binding.owner,
            repository = binding.repository,
            branch = binding.branch,
            workflowId = binding.workflowId
        )

        val prefix = keyPrefix(binding.projectId)

        val saved = preferences.edit()
            .putString(
                prefix + KEY_OWNER,
                binding.owner.trim()
            )
            .putString(
                prefix + KEY_REPOSITORY,
                binding.repository.trim()
            )
            .putString(
                prefix + KEY_BRANCH,
                binding.branch.trim()
            )
            .putLong(
                prefix + KEY_WORKFLOW_ID,
                binding.workflowId
            )
            .commit()

        check(saved) {
            "Could not save the project's GitHub configuration."
        }
    }

    /**
     * Removes the GitHub association for one project.
     * This does not delete the local project or GitHub repository.
     */
    fun remove(projectId: String) {
        validateProjectId(projectId)

        val prefix = keyPrefix(projectId)

        val removed = preferences.edit()
            .remove(prefix + KEY_OWNER)
            .remove(prefix + KEY_REPOSITORY)
            .remove(prefix + KEY_BRANCH)
            .remove(prefix + KEY_WORKFLOW_ID)
            .commit()

        check(removed) {
            "Could not remove the project's GitHub configuration."
        }
    }

    private fun validateProjectId(projectId: String) {
        require(PROJECT_ID_PATTERN.matches(projectId)) {
            "Invalid local project ID."
        }
    }

    private fun validateBinding(
        owner: String,
        repository: String,
        branch: String,
        workflowId: Long
    ) {
        require(
            REPOSITORY_PART_PATTERN.matches(owner.trim())
        ) {
            "Invalid GitHub owner."
        }

        require(
            REPOSITORY_PART_PATTERN.matches(repository.trim())
        ) {
            "Invalid GitHub repository name."
        }

        require(branch.isNotBlank()) {
            "GitHub branch is required."
        }

        require(branch.length <= 255) {
            "GitHub branch name is too long."
        }

        require(!branch.startsWith("-")) {
            "Invalid GitHub branch name."
        }

        require(
            branch.none {
                it.isWhitespace() ||
                    it == '~' ||
                    it == '^' ||
                    it == ':' ||
                    it == '?' ||
                    it == '*' ||
                    it == '[' ||
                    it == '\\'
            }
        ) {
            "GitHub branch contains invalid characters."
        }

        require(
            branch.split('/').none {
                it.isBlank() ||
                    it == "." ||
                    it == ".." ||
                    it.endsWith(".lock")
            }
        ) {
            "Invalid GitHub branch path."
        }

        require(workflowId > 0) {
            "A valid GitHub Actions workflow ID is required."
        }
    }

    private fun keyPrefix(projectId: String): String =
        "project_${projectId}_"
}

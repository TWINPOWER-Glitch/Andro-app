
package com.twinpower.andro

import android.content.Context
import org.json.JSONObject

/**
 * Persistent, non-secret GitHub connection state.
 *
 * OAuth credentials remain exclusively in GitHubSecureStore.
 * This class stores only account and repository selection metadata.
 */
internal data class GitHubConnectionSnapshot(
    val accountLogin: String = "",
    val repositoryFullName: String = "",
    val repositoryId: Long = 0L,
    val repositoryPrivate: Boolean = false,
    val defaultBranch: String = "main",
    val selectedBranch: String = "",
    val currentPath: String = "",
    val updatedAt: Long = 0L
)

internal object GitHubConnectionState {

    private const val PREFS_NAME = "andro_github_connection_state"
    private const val STATE_KEY = "connection_snapshot"
    private const val VERSION_KEY = "state_version"

    private const val CURRENT_VERSION = 1

    private val lock = Any()

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * Read the last saved connection state.
     *
     * No OAuth token is read or returned by this method.
     */
    fun read(context: Context): GitHubConnectionSnapshot? {
        synchronized(lock) {
            val prefs = preferences(context)

            val version = prefs.getInt(
                VERSION_KEY,
                CURRENT_VERSION
            )

            if (version != CURRENT_VERSION) {
                return null
            }

            val raw = prefs.getString(STATE_KEY, null)
                ?: return null

            return runCatching {
                val json = JSONObject(raw)

                GitHubConnectionSnapshot(
                    accountLogin = json.optString("account_login"),
                    repositoryFullName = json.optString(
                        "repository_full_name"
                    ),
                    repositoryId = json.optLong("repository_id"),
                    repositoryPrivate = json.optBoolean(
                        "repository_private"
                    ),
                    defaultBranch = json.optString(
                        "default_branch",
                        "main"
                    ),
                    selectedBranch = json.optString(
                        "selected_branch"
                    ),
                    currentPath = json.optString("current_path"),
                    updatedAt = json.optLong("updated_at")
                )
            }.getOrNull()
        }
    }

    /**
     * Save the selected GitHub account and repository.
     *
     * commit() is intentional: it waits for the preference write
     * to finish before returning.
     */
    fun save(
        context: Context,
        snapshot: GitHubConnectionSnapshot
    ): Boolean {
        require(
            snapshot.repositoryFullName.isBlank() ||
                snapshot.repositoryFullName.contains("/")
        ) {
            "Invalid GitHub repository name."
        }

        require(
            snapshot.selectedBranch.length <= 255
        ) {
            "Branch name is too long."
        }

        require(
            snapshot.currentPath.length <= 4096
        ) {
            "Repository path is too long."
        }

        synchronized(lock) {
            val json = JSONObject().apply {
                put("account_login", snapshot.accountLogin)
                put(
                    "repository_full_name",
                    snapshot.repositoryFullName
                )
                put("repository_id", snapshot.repositoryId)
                put(
                    "repository_private",
                    snapshot.repositoryPrivate
                )
                put("default_branch", snapshot.defaultBranch)
                put("selected_branch", snapshot.selectedBranch)
                put("current_path", snapshot.currentPath)
                put("updated_at", System.currentTimeMillis())
            }

            return preferences(context)
                .edit()
                .putInt(VERSION_KEY, CURRENT_VERSION)
                .putString(STATE_KEY, json.toString())
                .commit()
        }
    }

    /**
     * Update only the selected repository.
     */
    fun saveRepository(
        context: Context,
        repository: GitHubRepository
    ): Boolean {
        val previous = read(context)
            ?: GitHubConnectionSnapshot()

        return save(
            context,
            previous.copy(
                repositoryFullName = repository.fullName,
                repositoryId = repository.id,
                repositoryPrivate = repository.private,
                defaultBranch = repository.defaultBranch,
                selectedBranch = repository.defaultBranch,
                currentPath = ""
            )
        )
    }

    /**
     * Update the selected branch without discarding
     * the previously selected repository.
     */
    fun saveBranch(
        context: Context,
        branch: String
    ): Boolean {
        val previous = read(context)
            ?: return false

        return save(
            context,
            previous.copy(
                selectedBranch = branch
            )
        )
    }

    /**
     * Update the currently browsed repository directory.
     */
    fun savePath(
        context: Context,
        path: String
    ): Boolean {
        val previous = read(context)
            ?: return false

        return save(
            context,
            previous.copy(
                currentPath = path
            )
        )
    }

    /**
     * Save the authenticated GitHub account login.
     */
    fun saveAccount(
        context: Context,
        login: String
    ): Boolean {
        require(login.length <= 255) {
            "GitHub account name is too long."
        }

        val previous = read(context)
            ?: GitHubConnectionSnapshot()

        return save(
            context,
            previous.copy(accountLogin = login)
        )
    }

    /**
     * Clear repository selection while retaining
     * the saved account metadata.
     */
    fun clearRepository(context: Context): Boolean {
        val previous = read(context)
            ?: return true

        return save(
            context,
            previous.copy(
                repositoryFullName = "",
                repositoryId = 0L,
                repositoryPrivate = false,
                defaultBranch = "main",
                selectedBranch = "",
                currentPath = ""
            )
        )
    }

    /**
     * Clear all non-secret GitHub connection metadata.
     *
     * This does not clear the OAuth token.
     */
    fun clear(context: Context): Boolean {
        synchronized(lock) {
            return preferences(context)
                .edit()
                .remove(STATE_KEY)
                .remove(VERSION_KEY)
                .commit()
        }
    }
}

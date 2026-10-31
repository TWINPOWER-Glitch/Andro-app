
package com.twinpower.andro

import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BuildSnapshot(
    val id: String,
    val projectId: String,
    val task: String,
    val status: String,
    val logs: List<String>,
    val artifactPaths: List<String> = emptyList(),
    val exitCode: Int? = null
)

/**
 * Legacy build-manager compatibility layer.
 *
 * Android-device Gradle execution is intentionally disabled.
 * Actual Android builds must be dispatched to GitHub Actions
 * through the remote build integration.
 *
 * The existing public API is retained so current MCP call sites
 * continue compiling until they are migrated to remote builds.
 */
class BuildManager(
    @Suppress("unused")
    private val context: Context,
    private val repository: ProjectRepository
) {

    private val snapshots =
        ConcurrentHashMap<String, BuildSnapshot>()

    /**
     * Retained for compatibility with existing callers.
     *
     * This method does not execute Gradle locally. It records
     * that a remote GitHub Actions build is required.
     */
    suspend fun start(
        project: ProjectInfo,
        task: String
    ): BuildSnapshot {

        require(
            task.matches(
                Regex("[A-Za-z0-9:_-]{1,120}")
            )
        ) {
            "Invalid Gradle task."
        }

        // Confirm that the project still exists before recording
        // the build request.
        repository.projectDir(project.id)

        val id = UUID.randomUUID().toString()

        val snapshot = BuildSnapshot(
            id = id,
            projectId = project.id,
            task = task,
            status = "remote_required",
            logs = listOf(
                "Local Gradle execution is disabled.",
                "This project requires a GitHub Actions remote build.",
                "Requested Gradle task: $task",
                "No build has been dispatched yet."
            ),
            artifactPaths = emptyList(),
            exitCode = null
        )

        snapshots[id] = snapshot

        return snapshot
    }

    /**
     * Returns the compatibility snapshot for an existing request.
     */
    fun get(id: String): BuildSnapshot? =
        snapshots[id]
}

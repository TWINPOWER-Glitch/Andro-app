package com.twinpower.andro

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        context.getDir("projects", Context.MODE_PRIVATE)
            .deleteRecursively()

        repository = ProjectRepository(context)
    }

    @Test
    fun createProjectPersistsMetadataAndSyncHistory() = runBlocking {
        val project = repository.createProject("Test Project")

        val initial = repository.getMetadata(project.id)

        assertEquals("local", initial.source)
        assertEquals("not_synced", initial.syncState)
        assertTrue(initial.createdAt > 0L)

        repository.markProjectOpened(project.id)

        val opened = repository.getMetadata(project.id)
        assertNotNull(opened.lastOpenedAt)

        repository.markSyncStarted(project.id)

        val syncing = repository.getMetadata(project.id)
        assertEquals("syncing", syncing.syncState)

        repository.markSyncSucceeded(
            project.id,
            "Test synchronization completed."
        )

        val succeeded = repository.getMetadata(project.id)
        assertEquals("synced", succeeded.syncState)
        assertNotNull(succeeded.lastSyncedAt)

        val successfulSyncTime = succeeded.lastSyncedAt

        repository.markSyncFailed(
            project.id,
            "Simulated synchronization failure."
        )

        val failed = repository.getMetadata(project.id)

        assertEquals("failed", failed.syncState)
        assertEquals(
            "Simulated synchronization failure.",
            failed.lastSyncMessage
        )

        // A later failure must not erase the last successful sync time.
        assertEquals(
            successfulSyncTime,
            failed.lastSyncedAt
        )
    }

    @Test
    fun projectPathValidationRejectsTraversal() {
        runBlocking {
            val project = repository.createProject("Path Test")

            assertThrows(IllegalArgumentException::class.java) {
                repository.readFile(
                    project.id,
                    "../outside.txt"
                )
            }

            assertThrows(IllegalArgumentException::class.java) {
                repository.readFile(
                    project.id,
                    "/etc/passwd"
                )
            }

            assertThrows(IllegalArgumentException::class.java) {
                repository.projectDir("../../outside")
            }
        }
    }

    @Test
    fun githubBindingIsValidatedPersistedAndRemoved() = runBlocking {
        val project = repository.createProject("Binding Test")

        val binding = ProjectGitHubBinding(
            projectId = project.id,
            owner = "TWINPOWER-Glitch",
            repository = "Andro-app",
            branch = "main",
            workflowId = 123456L
        )

        repository.saveGitHubBinding(binding)

        val saved = repository.getGitHubBinding(project.id)

        assertNotNull(saved)
        assertEquals(binding.projectId, saved?.projectId)
        assertEquals(binding.owner, saved?.owner)
        assertEquals(binding.repository, saved?.repository)
        assertEquals(binding.branch, saved?.branch)
        assertEquals(binding.workflowId, saved?.workflowId)

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveGitHubBinding(
                binding.copy(owner = "../attacker")
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveGitHubBinding(
                binding.copy(branch = "feature/../main")
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveGitHubBinding(
                binding.copy(workflowId = 0L)
            )
        }

        repository.removeGitHubBinding(project.id)

        assertNull(repository.getGitHubBinding(project.id))
    }
}


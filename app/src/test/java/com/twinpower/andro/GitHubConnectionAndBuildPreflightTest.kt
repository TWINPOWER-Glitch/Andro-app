
package com.twinpower.andro

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GitHubConnectionAndBuildPreflightTest {

    private lateinit var context: Context
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        context.getSharedPreferences(
            "andro_github_connection_state",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        context.getSharedPreferences(
            "andro_github_secure",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        context.getDir("projects", Context.MODE_PRIVATE)
            .deleteRecursively()

        repository = ProjectRepository(context)
    }

    @Test
    fun connectionSnapshotPersistsAndRestores() {
        val snapshot = GitHubConnectionSnapshot(
            accountLogin = "test-account",
            repositoryFullName = "TWINPOWER-Glitch/Andro-app",
            repositoryId = 123456L,
            repositoryPrivate = true,
            defaultBranch = "main",
            selectedBranch = "feature/test",
            currentPath = "app/src",
            updatedAt = 1L
        )

        assertTrue(
            GitHubConnectionState.save(context, snapshot)
        )

        val restored = GitHubConnectionState.read(context)

        assertNotNull(restored)
        assertEquals("test-account", restored?.accountLogin)
        assertEquals(
            "TWINPOWER-Glitch/Andro-app",
            restored?.repositoryFullName
        )
        assertEquals(123456L, restored?.repositoryId)
        assertEquals(true, restored?.repositoryPrivate)
        assertEquals("main", restored?.defaultBranch)
        assertEquals("feature/test", restored?.selectedBranch)
        assertEquals("app/src", restored?.currentPath)
        assertTrue(restored!!.updatedAt > 0L)
    }

    @Test
    fun repositorySelectionPreservesAuthenticatedAccount() {
        assertTrue(
            GitHubConnectionState.saveAccount(
                context,
                "test-account"
            )
        )

        val selectedRepository = GitHubRepository(
            id = 987654L,
            name = "Andro-app",
            fullName = "TWINPOWER-Glitch/Andro-app",
            private = true,
            defaultBranch = "main",
            htmlUrl = "https://github.com/TWINPOWER-Glitch/Andro-app"
        )

        assertTrue(
            GitHubConnectionState.saveRepository(
                context,
                selectedRepository
            )
        )

        val restored = GitHubConnectionState.read(context)

        assertEquals("test-account", restored?.accountLogin)
        assertEquals(
            "TWINPOWER-Glitch/Andro-app",
            restored?.repositoryFullName
        )
        assertEquals(987654L, restored?.repositoryId)
        assertEquals("main", restored?.selectedBranch)
    }

    @Test
    fun clearingRepositoryRetainsAccountMetadata() {
        GitHubConnectionState.save(
            context,
            GitHubConnectionSnapshot(
                accountLogin = "test-account",
                repositoryFullName = "TWINPOWER-Glitch/Andro-app",
                repositoryId = 123456L,
                repositoryPrivate = true,
                defaultBranch = "main",
                selectedBranch = "main",
                currentPath = "app"
            )
        )

        assertTrue(
            GitHubConnectionState.clearRepository(context)
        )

        val restored = GitHubConnectionState.read(context)

        assertNotNull(restored)
        assertEquals("test-account", restored?.accountLogin)
        assertEquals("", restored?.repositoryFullName)
        assertEquals(0L, restored?.repositoryId)
        assertEquals("", restored?.selectedBranch)
        assertEquals("", restored?.currentPath)
    }

    @Test
    fun connectionStateRejectsOversizedBranchAndPath() {
        val oversizedBranch = GitHubConnectionSnapshot(
            repositoryFullName = "owner/repository",
            selectedBranch = "b".repeat(256)
        )

        val branchError = runCatching {
            GitHubConnectionState.save(
                context,
                oversizedBranch
            )
        }.exceptionOrNull()

        assertTrue(branchError is IllegalArgumentException)

        val oversizedPath = GitHubConnectionSnapshot(
            repositoryFullName = "owner/repository",
            currentPath = "p".repeat(4097)
        )

        val pathError = runCatching {
            GitHubConnectionState.save(
                context,
                oversizedPath
            )
        }.exceptionOrNull()

        assertTrue(pathError is IllegalArgumentException)
    }

    @Test
    fun remoteBuildIsUnavailableWithoutRepositoryBinding() {
        runBlocking {
            val project = repository.createProject(
                "Unbound Project"
            )

            val service = GitHubMcpBuildService(
                context,
                repository
            )

            assertFalse(
                service.canBuildProject(project.id)
            )

            val error = runCatching {
                service.buildProject(project.id)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(
                error!!.message!!.contains(
                    "no saved GitHub repository configuration"
                )
            )
        }
    }

    @Test
    fun remoteBuildRequiresAuthenticationEvenWithBinding() {
        runBlocking {
            val project = repository.createProject(
                "Unauthenticated Project"
            )

            repository.saveGitHubBinding(
                ProjectGitHubBinding(
                    projectId = project.id,
                    owner = "TWINPOWER-Glitch",
                    repository = "Andro-app",
                    branch = "main",
                    workflowId = 123456L
                )
            )

            val service = GitHubMcpBuildService(
                context,
                repository
            )

            assertFalse(
                service.canBuildProject(project.id)
            )

            val error = runCatching {
                service.buildProject(project.id)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(
                error!!.message!!.contains(
                    "GitHub is not connected"
                )
            )
        }
    }
}

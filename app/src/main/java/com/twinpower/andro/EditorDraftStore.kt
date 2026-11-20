package com.twinpower.andro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EditorDraft(
    val projectId: String,
    val path: String,
    val baseContentHash: String,
    val content: String
)

data class EditorFileConflict(
    val projectId: String,
    val path: String,
    val latestContent: String
)

class EditorFileChangedException(
    val latestContent: String
) : IOException("The file changed outside the editor.")

/** Persists recoverable editor buffers separately from project source files. */
class EditorDraftStore(context: Context) {

    private val draftsDir = File(
        context.applicationContext.filesDir,
        "editor-drafts"
    )

    private val mutex = Mutex()
    private val persistenceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    fun persistNow(
        projectId: String,
        path: String,
        baseContent: String,
        content: String
    ) {
        if (baseContent == content) {
            markDirty(projectId, path, false)
            return
        }

        markDirty(projectId, path, true)
        val stateKey = stateKey(projectId, path)
        val expectedVersion = dirtyVersions[stateKey] ?: return
        persistenceScope.launch {
            runCatching {
                save(
                    projectId,
                    path,
                    baseContent,
                    content,
                    expectedVersion
                )
            }
        }
    }

    fun markDirty(
        projectId: String,
        path: String,
        dirty: Boolean
    ) {
        val key = stateKey(projectId, path)
        dirtyVersions[key] = (dirtyVersions[key] ?: 0L) + 1L
        if (dirty) processDirtyDrafts.add(key) else processDirtyDrafts.remove(key)
    }

    suspend fun hasProjectDrafts(projectId: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(projectId.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
                    "Invalid project ID."
                }

                val prefix = "$projectId\u0000"
                if (processDirtyDrafts.any { it.startsWith(prefix) }) {
                    return@withLock true
                }

                draftsDir.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.extension == "json" }
                    ?.any { file ->
                        runCatching {
                            JSONObject(file.readText(Charsets.UTF_8))
                                .optString("projectId") == projectId
                        }.getOrDefault(false)
                    }
                    ?: false
            }
        }

    suspend fun read(
        projectId: String,
        path: String
    ): EditorDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = draftFile(projectId, path)
            if (!file.isFile) return@withLock null

            val json = JSONObject(file.readText(Charsets.UTF_8))
            val draft = EditorDraft(
                projectId = json.getString("projectId"),
                path = json.getString("path"),
                baseContentHash = json.getString("baseContentHash"),
                content = json.getString("content")
            )

            require(
                draft.projectId == projectId && draft.path == path
            ) {
                "Draft metadata does not match its file."
            }

            require(draft.content.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES) {
                "The saved draft exceeds the recovery limit."
            }

            draft
        }
    }

    suspend fun save(
        projectId: String,
        path: String,
        baseContent: String,
        content: String,
        expectedVersion: Long? = null
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stateKey = stateKey(projectId, path)
            if (expectedVersion != null &&
                (dirtyVersions[stateKey] != expectedVersion ||
                    !processDirtyDrafts.contains(stateKey))
            ) {
                return@withLock
            }

            if (baseContent == content) {
                markDirty(projectId, path, false)
                draftFile(projectId, path).delete()
                return@withLock
            }

            val bytes = content.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_DRAFT_BYTES) {
                "Draft is too large to save for recovery."
            }

            check(draftsDir.exists() || draftsDir.mkdirs()) {
                "Could not create the editor draft directory."
            }

            val destination = draftFile(projectId, path)
            val temporary = File(
                draftsDir,
                ".${destination.name}.${UUID.randomUUID()}.tmp"
            )
            val backup = File(
                draftsDir,
                ".${destination.name}.${UUID.randomUUID()}.bak"
            )

            val json = JSONObject()
                .put("projectId", projectId)
                .put("path", path)
                .put("baseContentHash", contentHash(baseContent))
                .put("content", content)

            try {
                FileOutputStream(temporary).use { output ->
                    output.write(json.toString().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }

                if (destination.exists()) {
                    check(destination.renameTo(backup)) {
                        "Could not preserve the previous editor draft."
                    }
                }

                if (!temporary.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    error("Could not save the editor draft.")
                }

                backup.delete()
            } finally {
                temporary.delete()
                if (backup.exists() && !destination.exists()) {
                    backup.renameTo(destination)
                }
                if (backup.exists() && destination.exists()) {
                    backup.delete()
                }
            }
        }
    }

    suspend fun delete(
        projectId: String,
        path: String
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            markDirty(projectId, path, false)
            draftFile(projectId, path).delete()
        }
    }

    fun matchesCurrentFile(
        draft: EditorDraft,
        currentContent: String
    ): Boolean = draft.baseContentHash == contentHash(currentContent)

    private fun draftFile(
        projectId: String,
        path: String
    ): File {
        require(projectId.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
            "Invalid project ID."
        }
        require(path.isNotBlank() && path.length <= 240) {
            "Invalid editor path."
        }

        val key = contentHash(stateKey(projectId, path))
        return File(draftsDir, "$key.json")
    }

    private fun stateKey(projectId: String, path: String): String {
        require(projectId.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
            "Invalid project ID."
        }
        require(path.isNotBlank() && path.length <= 240) {
            "Invalid editor path."
        }
        return "$projectId\u0000$path"
    }

    private fun contentHash(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_DRAFT_BYTES = 1024 * 1024
        val processDirtyDrafts = ConcurrentHashMap.newKeySet<String>()
        val dirtyVersions = ConcurrentHashMap<String, Long>()
    }
}

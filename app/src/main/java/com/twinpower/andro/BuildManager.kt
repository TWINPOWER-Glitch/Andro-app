package com.twinpower.andro

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

class BuildManager(
    private val context: Context,
    private val repository: ProjectRepository
) {
    private val snapshots =
        ConcurrentHashMap<String, BuildSnapshot>()

    private val buildScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(
        project: ProjectInfo,
        task: String
    ): BuildSnapshot {
        require(
            task.matches(
                Regex("[A-Za-z0-9:_-]{1,120}")
            )
        ) {
            "Invalid Gradle task"
        }

        val runtime = repository.toolchainStatus()

        check(runtime.ready) {
            runtime.message
        }

        val id = UUID.randomUUID().toString()

        val initial = BuildSnapshot(
            id = id,
            projectId = project.id,
            task = task,
            status = "queued",
            logs = listOf("Queued local Gradle task: $task")
        )

        snapshots[id] = initial

        buildScope.launch {
            execute(id, project, task)
        }

        return initial
    }

    fun get(id: String): BuildSnapshot? =
        snapshots[id]

    private suspend fun execute(
        id: String,
        project: ProjectInfo,
        task: String
    ) = withContext(Dispatchers.IO) {

        val root = repository.projectDir(project.id)
        val toolchain = repository.toolchainDir

        val bash = File(toolchain, "bin/bash")
        val gradle = File(toolchain, "bin/gradle")
        val sdk = File(toolchain, "android-sdk")
        val wrapper = File(root, "gradlew")

        val command = if (wrapper.exists()) {
            listOf(
                bash.absolutePath,
                wrapper.absolutePath,
                "--no-daemon",
                task
            )
        } else {
            listOf(
                bash.absolutePath,
                gradle.absolutePath,
                "--no-daemon",
                task
            )
        }

        update(
            id,
            "running",
            "Starting local Gradle process."
        )

        try {
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] =
                        context.filesDir.absolutePath

                    environment()["JAVA_HOME"] =
                        toolchain.absolutePath

                    environment()["ANDROID_HOME"] =
                        sdk.absolutePath

                    environment()["ANDROID_SDK_ROOT"] =
                        sdk.absolutePath

                    environment()["GRADLE_USER_HOME"] =
                        File(
                            context.filesDir,
                            "gradle-cache"
                        ).absolutePath

                    environment()["PATH"] = listOf(
                        File(toolchain, "bin").absolutePath,
                        File(sdk, "platform-tools").absolutePath,
                        File(
                            sdk,
                            "cmdline-tools/latest/bin"
                        ).absolutePath,
                        System.getenv("PATH") ?: ""
                    ).joinToString(File.pathSeparator)

                    environment()["LD_LIBRARY_PATH"] =
                        File(
                            toolchain,
                            "lib"
                        ).absolutePath

                    environment()["JAVA_OPTS"] =
                        "-Djava.io.tmpdir=${context.cacheDir.absolutePath}"
                }
                .start()

            val logs = mutableListOf<String>()

            process.inputStream
                .bufferedReader()
                .useLines { lines ->
                    lines.forEach { line ->
                        if (logs.size < 2000) {
                            logs.add(line)

                            snapshots[id]?.let { old ->
                                snapshots[id] = old.copy(
                                    logs = logs.toList()
                                )
                            }
                        }
                    }
                }

            val code = process.waitFor()

            val artifacts = root.walkTopDown()
                .filter {
                    it.isFile &&
                        (
                            it.extension.equals("apk", true) ||
                                it.extension.equals("aab", true)
                            ) &&
                        it.path.contains(
                            "${File.separator}build${File.separator}outputs${File.separator}"
                        )
                }
                .map {
                    it.absolutePath
                }
                .toList()

            snapshots[id]?.let { old ->
                snapshots[id] = old.copy(
                    status = if (code == 0) {
                        "succeeded"
                    } else {
                        "failed"
                    },
                    exitCode = code,
                    logs = logs + "Gradle exited with code $code.",
                    artifactPaths = artifacts
                )
            }
        } catch (error: Exception) {
            update(
                id,
                "failed",
                "Build launch failed: ${error.message}"
            )
        }
    }

    private fun update(
        id: String,
        status: String,
        line: String
    ) {
        snapshots[id]?.let { old ->
            snapshots[id] = old.copy(
                status = status,
                logs = old.logs + line
            )
        }
    }
}

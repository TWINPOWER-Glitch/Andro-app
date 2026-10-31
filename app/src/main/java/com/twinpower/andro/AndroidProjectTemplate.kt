package com.twinpower.andro

import java.io.File

/**
 * Generates a minimal, conventional Gradle Android application project.
 *
 * The installed Andro toolchain supplies Gradle, Java and the Android SDK.
 * A Gradle wrapper is intentionally not fabricated: its JAR must come
 * from the official Gradle wrapper distribution.
 */
object AndroidProjectTemplate {

    fun generate(
        destination: File,
        projectName: String
    ) {
        val packageName = packageNameFor(projectName)
        val appName = projectName.trim().ifBlank { "AndroApp" }

        write(
            destination,
            "settings.gradle.kts",
            """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }

                rootProject.name = "${escapeGradle(appName)}"
                include(":app")
            """.trimIndent()
        )

        write(
            destination,
            "build.gradle.kts",
            """
                plugins {
                    id("com.android.application") version "8.9.2" apply false
                    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
                }
            """.trimIndent()
        )

        write(
            destination,
            "gradle.properties",
            """
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
            """.trimIndent()
        )

        write(
            destination,
            "app/build.gradle.kts",
            """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$packageName"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$packageName"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }

                    kotlinOptions {
                        jvmTarget = "17"
                    }
                }
            """.trimIndent()
        )

        write(
            destination,
            "app/src/main/AndroidManifest.xml",
            """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:allowBackup="true"
                        android:label="${escapeXml(appName)}"
                        android:supportsRtl="true"
                        android:theme="@android:style/Theme.Material.Light.NoActionBar">

                        <activity
                            android:name=".MainActivity"
                            android:exported="true">

                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>

                        </activity>
                    </application>
                </manifest>
            """.trimIndent()
        )

        write(
            destination,
            "app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt",
            """
                package $packageName

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)

                        val message = TextView(this).apply {
                            text = "Your Android project is ready."
                            textSize = 20f
                            setPadding(32, 32, 32, 32)
                        }

                        setContentView(message)
                    }
                }
            """.trimIndent()
        )

        write(
            destination,
            ".gitignore",
            """
                .gradle/
                **/build/
                local.properties
                *.iml
                .idea/
            """.trimIndent()
        )
    }

    private fun packageNameFor(name: String): String {
        val parts = name.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .map { part ->
                if (part.first().isDigit()) {
                    "app$part"
                } else {
                    part
                }
            }
            .ifEmpty {
                listOf("androapp")
            }

        return (listOf("com") + parts).joinToString(".")
    }

    private fun write(
        root: File,
        relativePath: String,
        content: String
    ) {
        val target = File(root, relativePath)

        check(
            target.canonicalPath.startsWith(
                root.canonicalPath + File.separator
            )
        ) {
            "Template path escapes project directory."
        }

        check(
            target.parentFile?.exists() == true ||
                target.parentFile?.mkdirs() == true
        ) {
            "Could not create template directory for $relativePath."
        }

        target.writeText(
            content.trimIndent() + "\n",
            Charsets.UTF_8
        )
    }

    private fun escapeGradle(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}

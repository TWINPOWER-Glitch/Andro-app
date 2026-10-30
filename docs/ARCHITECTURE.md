# Andro architecture and implementation boundaries

## Local-first core
The app owns its project workspace under `filesDir/projects`. SAF imports are copied into private app storage so Gradle does not build directly from the Android shared-storage mount (which is often `noexec` and has different filesystem semantics).

`BuildManager` invokes a compatible local ARM64 toolchain from `filesDir/toolchain`. It runs the project's `gradlew` through the toolchain's Bash when available, or the installed system Gradle binary otherwise. It sets `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `PATH`, and `LD_LIBRARY_PATH`.

## Toolchain contract
Expected layout:
- `bin/bash`
- `bin/java`
- `bin/gradle` (fallback when no wrapper is present)
- `lib/` native libraries
- `android-sdk/platforms`
- `android-sdk/build-tools`

A real Android build runtime must supply ARM64 Android-compatible executables and libraries. x86_64 desktop SDK binaries will not work on ARM64 Android. The toolchain importer is intentionally explicit; automatic provisioning and checksum/signature verification are not implemented yet.

## MCP
The local server implements the MCP JSON-RPC methods `initialize`, `ping`, `tools/list`, and `tools/call`, plus notifications. It binds to `127.0.0.1:8765`, and is only active while the app keeps the server running. This avoids exposing a development endpoint to a network by default.

Tools:
- `andro_list_projects`
- `andro_get_project`
- `andro_list_project_files`
- `andro_read_project_file`
- `andro_build_project`
- `andro_get_build_status`

## Security backlog
Before LAN or cloud-relayed MCP access:
- require per-client bearer authentication and explicit pairing;
- bind to a selected interface only after user consent;
- rate-limit requests and cap file reads;
- require confirmation for writes, deletes, signing, and release operations;
- add audit logs and revokeable client credentials.

## Next milestones
1. Toolchain bootstrap: select a redistributable ARM64 Android-compatible JDK, Gradle distribution, SDK platform/build tools, and compatible native aapt2; verify every download with pinned SHA-256/signatures.
2. Gradle compatibility matrix: validate representative Gradle wrapper, AGP, Kotlin, Compose, Java, and SDK combinations on actual ARM64 devices.
3. Project editor and file mutation tools with explicit approval.
4. Git CLI or embedded JGit integration; GitHub authentication and remote sync.
5. GitHub Actions as an optional build provider.
6. Optional Vercel connection, account authentication, cloud sync, and hosted builds.
7. Background MCP service and secure remote relay.

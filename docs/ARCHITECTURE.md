# Andro architecture and current boundaries

This document describes the implementation in the repository today. Andro is a mobile project workspace with GitHub-backed remote builds. It does not currently run Gradle builds on the Android device.

## Workspace and editing

`ProjectRepository` owns project files under the app's private `filesDir/projects` directory. New projects are generated from `AndroidProjectTemplate`. Imports use Android's Storage Access Framework and copy selected project trees into private storage; `.git`, `.gradle`, and `build` directories are skipped during import.

The Compose workspace browses and edits files and supports basic create, rename, and delete operations. Repository methods validate project IDs and canonicalize project-relative paths. Editor file reads and writes, and MCP file reads, are limited to 512 KB per file. MCP currently has no file-write tool, and importing a project uses a separate copy path.

Project scans, editor I/O, and file mutations run on `Dispatchers.IO`. Unsaved text is debounced into app-private draft files and persisted again when the activity stops or is destroyed; the selected project and editor path are saved across recreation so the draft can be restored. Drafts include a hash of the file version they were based on. Matching drafts restore automatically; if the project file changed, Andro asks before recovering or discarding the draft. A save compares the current file with the editor's base version and uses the same repository monitor as GitHub pull writes, preventing an unnoticed overwrite from a concurrent pull. Renaming or deleting the open file is blocked while it has unsaved edits.

The editor is not a language-aware IDE and does not provide Git diff/merge tooling.

## Authentication and GitHub

There are two distinct authentication flows:

- The launcher uses Supabase Auth with GitHub sign-in and PKCE for the Andro account session.
- Repository browsing, project sync, workflow dispatch, status, and artifact access use Andro's separate GitHub device-flow connection.

`GitHubProjectSync` uses GitHub repository APIs to move project files and create commits. It is not a local Git checkout and does not implement merge, rebase, or cherry-pick. Project-to-repository associations are stored separately from the GitHub token. Pushes and remote builds are blocked while a project has an unsaved editor draft.

`GitHubWorkflowManager` and `GitHubBuildOrchestrator` manage Actions workflows, runs, and artifacts. A project must have a saved repository/workflow binding and GitHub authorization before a remote build can be dispatched. The app can install its Android build workflow into a repository.

The build screen restores the selected project and tracks the dispatched workflow, branch, timestamp, and run ID across activity recreation. After GitHub accepts a dispatch, Andro looks for the matching run; if GitHub has not exposed it yet, the user can refresh the run list. Failed and cancelled conclusions are summarized, with a direct link to GitHub job logs. Artifact downloads show the extracted destination and are staged with size and path-safety limits before being moved into the project.

## Build execution

`BuildManager` remains as a compatibility layer for older callers. It does not start a process or invoke Gradle; it returns a `remote_required` record. Actual Android builds are dispatched to GitHub Actions through the GitHub build integration.

The toolchain importer can store and inspect a toolchain directory, but it does not enable on-device builds. Running Gradle on Android would require a verified Android-compatible JDK, Gradle runtime, Android SDK tools, and native build tools. Automatic provisioning and device compatibility validation are not implemented.

The CI workflow assembles debug and release APKs. The release APK is unsigned; release signing and Play Store distribution are not configured.

## MCP and remote relay

`AndroMcpServer` is a Ktor CIO server bound to `127.0.0.1:8765`. It requires a per-instance bearer token for MCP requests. The token is generated in memory and is available to trusted app components; the UI does not expose it as a credential for arbitrary local clients. The server also has a public health endpoint that returns service status only.

Current MCP tools:

- `andro_list_projects`
- `andro_get_project`
- `andro_list_project_files`
- `andro_read_project_file`
- `andro_build_project` — syncs a bound project and dispatches a GitHub Actions workflow
- `andro_get_remote_build_status` — returns the actual Actions run status and available artifacts
- `andro_get_build_status` — legacy request record only; it is not live Actions status

`CloudflareMcpRelay` provides optional remote access. The user pairs the device, connects the relay, and creates distinct client tokens. Client access can be read-only or include remote build permission; tokens can be revoked. Device credentials are stored encrypted with Android Keystore. The relay is controlled from the app UI and is not implemented as a persistent foreground service, so remote access depends on Andro remaining active.

The local server remains loopback-only. Do not bind it to a LAN or public interface without a deliberate authentication, authorization, transport-security, and lifecycle design.

## Current engineering priorities

1. Make sync reviewable: show which files a pull or push will change and detect local/remote overlap before applying changes.
2. Harden MCP operations with bounded request sizes and sensible rate limits. Add a foreground service only if persistent background relay access is a product requirement.
3. Treat on-device builds as separate research: select a compatible runtime and validate it on supported ARM64 devices before promising local compilation.

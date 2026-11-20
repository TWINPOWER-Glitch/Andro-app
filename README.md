# Andro

Andro is an Android app for managing Android projects from a phone. It keeps project files in the app's private storage, provides a lightweight source editor, syncs projects with GitHub, and uses GitHub Actions for Android builds. It also offers an optional MCP connection for local or remote clients.

Andro is currently a development foundation, not a Play Store release.

## What works today

- Sign in to an Andro account with GitHub through Supabase Auth using the PKCE flow.
- Create a project from Andro's Android project template, or import a project through Android's Storage Access Framework.
- Browse, create, edit, rename, and delete project files in Andro's private workspace.
- Recover unsaved editor drafts after app recreation. Before saving, Andro checks whether the file changed on disk and asks whether to reload or explicitly overwrite the newer version.
- Connect GitHub separately to browse repositories and branches, associate a project with a repository, and push or pull project files.
- Install or select a GitHub Actions workflow, dispatch a remote build, restore the selected project/run after activity recreation, inspect failures with a link to GitHub job logs, and download artifacts into the project.
- Start an authenticated MCP endpoint on `127.0.0.1:8765` while using the app. Andro exposes project listing and file-reading tools, plus remote build and status tools.
- Optionally pair Andro with the Cloudflare MCP relay to connect remote MCP clients. Client tokens can be created with read-only access or with build access and can be revoked.

## Build model and current limitations

**Andro does not build Android projects on the phone.** `BuildManager` is a compatibility layer and reports that local Gradle execution is disabled. Android builds run in GitHub Actions, so a working GitHub connection, repository binding, and configured workflow are required.

The app can import and store a toolchain directory, but that does not enable local builds. A conventional desktop Android SDK is not an Android-device runtime; an Android-compatible JDK, Gradle runtime, SDK tools, and native build tools would need to be provided and validated before on-device builds could be supported.

Other current limits:

- The editor is a lightweight file editor, not a full IDE with language services or debugging.
- Editor file reads and writes, and MCP file reads, enforce a 512 KB limit per file. MCP currently has no file-write tool; project import uses a separate copy path.
- GitHub sync uses GitHub's repository APIs; Andro is not a full Git client and does not provide merge, rebase, or cherry-pick workflows.
- A push or remote build is blocked while the project has unsaved editor changes, so it cannot silently build or publish the older on-disk file.
- The MCP server is loopback-only and requires bearer authorization. Its local authorization token is not currently presented as a user-configurable credential for arbitrary local MCP clients. The Cloudflare relay provides the user-facing remote pairing and scoped client-token flow.
- Remote MCP access depends on the app staying active; the relay is not implemented as a persistent Android foreground service.
- The CI release APK is unsigned. Release signing and Play Store distribution are not configured.

## Accounts and connections

Andro account sign-in and GitHub repository access are separate:

1. The launcher asks you to sign in to an Andro account using GitHub through Supabase Auth.
2. Repository browsing, project sync, and GitHub Actions builds use the separate GitHub connection in the **Connect** screen.
3. The Cloudflare relay is optional. Pair a device before creating MCP client tokens. Grant build access only to clients that need to start remote builds, and revoke tokens that are no longer needed.

The Supabase key configured in `AndroSupabase.kt` is a publishable client key. Do not put a Supabase service-role key, a private GitHub credential, or a Cloudflare secret in the Android source or repository.

## Use Andro

1. Sign in, then create a project or import an existing Android Gradle project.
2. In **Connect**, authorize GitHub for repository operations.
3. In **Build**, associate the project with a repository and workflow. Andro can install its build workflow into a selected repository.
4. Push or pull project files as needed, then dispatch a remote build and monitor the GitHub Actions run. Download the artifact after the run uploads it.
5. For MCP access, use the local endpoint only with an authorized client, or configure the Cloudflare relay for a remote client.

Remote builds require GitHub Actions to be available and enabled for the selected repository. Workflow permissions and repository policies remain subject to the GitHub account's access.

## Run the Android app

Open the repository root in Android Studio. The project uses:

- JDK 17
- Android Gradle Plugin 8.9.2
- Kotlin 2.1.20
- Android SDK platform 36
- Gradle 8.13 in CI

The app has `minSdk 26` and `targetSdk 35`. Run the `app` configuration from Android Studio. The CI workflow runs unit tests, Android lint, debug and release APK assembly, and an emulator smoke check of the launcher/authentication screen.

## MCP connections

The local endpoint is:

```text
http://127.0.0.1:8765/mcp
```

It listens only on the device loopback interface. MCP requests require an `Authorization: Bearer …` header. The endpoint is not exposed to the LAN, and the app does not currently provide a self-service local token for arbitrary clients. Do not change the bind address or expose the endpoint publicly without a designed authentication and transport-security model.

For remote MCP access, use **Connect → Cloudflare relay** to pair the device, connect it, and create a scoped client token. The token is shown once; store it securely. Keep Andro active while you need the relay connection.

## CI and releases

`.github/workflows/andro-app-build-check.yml` runs tests, lint, APK builds, and an Android emulator smoke check on relevant pushes and pull requests. Version tags must match the app's `versionName` (for example, `v0.4.0`). A matching tag publishes the debug APK and an **unsigned** release APK as GitHub release assets. These artifacts are for development and testing; they are not Play Store-ready packages.

## Project layout

```text
app/src/main/java/com/twinpower/andro/  Android app, workspace, GitHub, and MCP code
app/src/test/                           JVM/Robolectric unit tests
.github/workflows/                      Android CI and release workflow
```

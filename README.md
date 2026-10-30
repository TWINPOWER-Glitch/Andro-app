# Andro — standalone Android development environment

This project replaces the previous Vercel-dependent dashboard with an Android-first application.

## Implemented in this increment

- Local project workspace stored in the app's private files directory.
- Import a Gradle project through Android's Storage Access Framework.
- Import a compatible Android build toolchain directory.
- Inspect imported project files.
- Run Gradle tasks locally through the imported toolchain.
- Capture build output, status, and APK/AAB paths.
- Embedded, loopback-only MCP Streamable HTTP endpoint with tools for listing projects, inspecting files, reading files, and starting/checking local builds.
- No Vercel server or GitHub account is required for these local functions.

## Important build-runtime limitation

The Android application cannot assume that desktop Android Studio's JDK/SDK binaries will execute on Android. A compatible ARM64/Android (Bionic) runtime is required. This source includes the local process/build adapter and a toolchain-import mechanism, but **does not yet bundle or automatically provision the JDK, Gradle, Android SDK, and compatible ARM64 `aapt2`**. Until a compatible runtime is imported, the app correctly reports that local builds are unavailable instead of pretending to build.

The expected imported toolchain layout is:

```text
toolchain/
  bin/
    bash
    java
    gradle                 # optional if the project has gradlew
  lib/                     # native shared libraries required by the runtime
  android-sdk/
    platforms/
    build-tools/
```

The toolchain must be built for the device architecture and Android's runtime environment. A conventional x86_64 Linux/Windows/macOS Android SDK installation is not suitable.

## Build

- Android Studio
- JDK 17
- Android SDK 36
- Android Gradle Plugin 8.9.2
- Gradle 8.11.1 or compatible

Open `android-app/` in Android Studio and run the `app` configuration.

## MCP endpoint

The embedded MCP server listens on `http://127.0.0.1:8765/mcp` while enabled in the foreground app. It is loopback-only in this increment. It implements stateless JSON responses for MCP initialization, tool discovery, and tool calls.

For a same-device client, connect to loopback. For development from a computer, use Android Debug Bridge port forwarding:

```bash
adb forward tcp:8765 tcp:8765
```

A cloud-hosted MCP client cannot reach a phone's loopback address. A future optional relay/tunnel or user-hosted reachable endpoint is needed for remote ChatGPT/Claude access. Do not expose this endpoint to a LAN or public network without authentication and transport security.

## Current scope

This is an implementation foundation, not a Play Store release. GitHub OAuth/version control, optional GitHub Actions, optional Vercel accounts/cloud sync, persistent background MCP service, release signing UI, and automatic runtime provisioning are subsequent milestones.

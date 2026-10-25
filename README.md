# Android Mirror

Private, local-Wi-Fi Android screen mirroring and optional remote control for Windows.

This repository contains a real protocol and transport implementation, not a simulated phone screen. The Android application uses Android's required `MediaProjection` consent prompt and WebRTC's screen-capture pipeline; its local WebSocket endpoint only accepts authenticated, paired computers. The desktop viewer uses the installed Windows WebView2 runtime's `RTCPeerConnection` API to decode the incoming WebRTC video.

## Status

Phase 0/1 source is present: authenticated LAN discovery/pairing foundation, Android foreground sharing service, WebRTC offer/answer signaling, desktop device list/viewer, and data-channel input protocol. It has **not** been run against a physical device in this Linux build environment because Android SDK, Gradle, Rust/Cargo, WebView2, and an Android device are unavailable here. See [docs/PROGRESS.md](docs/PROGRESS.md) and [docs/BUILD_AND_INSTALL.md](docs/BUILD_AND_INSTALL.md) before treating a build as validated.

## Safety boundaries

- Every screen-sharing session requires Android's system capture approval.
- Remote control requires the user to enable this app's Accessibility Service in Android settings. View-only sharing remains available without it.
- Lock screens, secure windows, DRM video, password fields, permission dialogs, and OEM restrictions are never bypassed.
- The LAN MVP has no account, analytics, cloud signaling, recording, clipboard sync, shell, or file-access feature.

## Layout

- `android/` — native Kotlin Android companion
- `desktop/` — Tauri 2, React and TypeScript Windows client
- `shared/` — versioned wire schemas and fixtures
- `relay/` — self-hostable signaling relay for a future remote mode
- `.github/workflows/build.yml` — cloud build workflow for APK and Windows bundles
- `docs/` — architecture, permission, security, build, test, limitation, and progress records
- `scripts/` — reproducible development helpers


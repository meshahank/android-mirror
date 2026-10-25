# Progress

## 2026-10-25 — Phase 0/first vertical-slice source

Created the monorepo and pinned Android (AGP 8.6.1, Kotlin 2.0.21, Ktor 2.3.12, Stream WebRTC 1.3.8) and desktop (Tauri 2.0.0, React 18.3.1, Vite 5.4.10, mdns-sd 0.11.5) dependencies. These versions are declared in build files but their downloads/builds cannot be validated here because this container has Java 17 and Node 24 only—no Android SDK, Gradle launcher/wrapper JAR, Rust/Cargo, WebView2 or connected phone.

Implemented source-level LAN mDNS advertising/discovery, manual IP entry, short-code pairing flow, Ktor local signalling, Android `MediaProjection` foreground service, WebRTC screen video source/offer-answer/ICE, WebView2 WebRTC receiver, viewer shell, and basic accessibility gestures/global navigation. Paired public-key identifiers are stored as SHA-256 fingerprints instead of raw values. Added protocol schema/fixture, Android protocol, and desktop coordinate mapping tests.

`scripts/check.sh` is runnable in this repository. It validates JSON protocol fixtures now; desktop/Android compile stages correctly report skipped until their local dependencies/toolchains are installed.

## Gate status

| Gate | Status | Evidence |
|---|---|---|
| Skeleton apps build | Not verified | Missing toolchains in this environment |
| Windows decodes real Android stream | Not verified | Requires physical Android + Windows/WebView2 test |
| LAN discovery / saved pairing | Partial source implementation | No hardware execution; production key/approval gaps remain |
| 10-minute real stream | Not run | Listed in manual test plan |
| Remote control | Partial source implementation | Accessibility gesture dispatch exists; secure/session semantics incomplete |

Next implementation step: build and test this exact tree on the Windows laptop/Redmi, fix API-level compile errors and validate WebRTC H.264 negotiation before extending any UI or internet mode.

## 2026-10-25 — remote infrastructure and cloud-build preparation

Added a self-hostable relay with envelope-size and identifier validation, per-socket rate limits, online-device routing, session isolation, health endpoint and a Node test. Added GitHub Actions to build the debug Android APK and Windows bundles on hosted runners, avoiding local SDK/toolchain storage.

Validated: `node --check relay/server.mjs`, `node --test relay/test/*.test.mjs`, and `scripts/check.sh` pass. Android and desktop compilation remain unverified here. The relay is infrastructure only: the apps are not wired to a secured administrator-provided remote endpoint and TURN credentials, so worldwide access is not represented as complete.

# Build and install

## Prerequisites

- Windows 11: Node 20+, Rust stable with MSVC build tools, WebView2 Runtime, Android Studio/JDK 17, Android SDK Platform 35 and an Android 11+ phone on the same Wi-Fi.
- Android Studio will download Gradle 8.7 and Maven dependencies on its first build. Use an Android SDK path in `android/local.properties`; never commit it.

## Android debug APK

1. Open `android/` in Android Studio and allow Gradle sync.
2. Build `app` → `assembleDebug`, then install `app/build/outputs/apk/debug/app-debug.apk`.
3. Open Android Mirror and choose **Start screen sharing**. Approve the Android system dialog. The sharing notification must remain visible.
4. Optionally open **Allow remote control** and explicitly enable Android Mirror in Accessibility settings. Do not enable it if you only need viewing.

## Windows app

1. In `desktop/`, run `npm install` then `npm run tauri build` on Windows.
2. Install the generated NSIS or MSI installer from `desktop/src-tauri/target/release/bundle/`.
3. Allow the application through the Windows Private-network firewall prompt. Open it, select the discovered phone (or enter its local IP), enter the code shown on the phone, and approve sharing.

This is intentionally not a cable/ADB workflow. If mDNS is filtered by the router, manual local IP is expected to work. Internet access is not part of this build.

## Build in the cloud (no local SDK storage)

Push this repository to a private GitHub repository and run **Actions → Build Android Mirror → Run workflow**. GitHub-hosted Windows and Linux runners build the APK and Windows bundle, then expose `Android-Mirror-APK` and `Android-Mirror-Windows` as artifacts. This avoids Android Studio, Android SDK, Rust and Node installations on your laptop.

The workflow is a compilation gate, not proof that mirroring works. Install the artifacts on the actual phone and Windows laptop, then perform the device checks in `TEST_PLAN.md`.

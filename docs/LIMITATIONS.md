# Limitations

- This environment could not run Android Gradle, Rust/Tauri, WebView2, or a physical Android phone. Builds and live interoperability are unverified.
- The initial service begins its local listener when sharing begins, so first pairing requires starting sharing before Windows connects. Separate pairing-only listener is a follow-up.
- Pair approval is short-code based in this initial vertical slice; a distinct accept/reject prompt and cryptographic key persistence are required before production use.
- Drag continuation, supported text entry, video adaptation controls, orientation metadata, reconnect/backoff, and event-rate limits are not yet complete.
- Android OEMs can interrupt capture for battery/background policy. Screen-off and secure/DRM behavior vary.
- No internet/NAT traversal is present. There is intentionally no remote unattended access.
- Relay source now exists, but WSS/TLS, device identity, secure remote pairing, TURN provisioning, background reliability and cross-network tests are incomplete. Do not expose it directly to the internet.

# Security model

The product is designed for personal, local-network use. It does not expose a remote shell, files, clipboard, recording, analytics or unauthenticated control endpoint. WebRTC media uses DTLS-SRTP. A sharing foreground notification includes Stop; the Android system can end capture at any time.

Input protocol constraints: messages are versioned and size-limited to 64 KiB; only the active socket can signal/control; session requests require a stored paired identifier. Production completion still requires request signatures, nonce/replay validation, per-event rate limits, encrypted Android pairing storage through Keystore, Windows DPAPI/Stronghold storage, HTTPS/WSS certificate pinning, explicit pending-pair approval, and authorization scoped to a session. These gaps are tracked, not hidden.

Do not port-forward TCP 38475. Phase 5 should use a separate authenticated signalling service plus STUN/TURN, keeping the LAN path private; TURN relays use paid bandwidth in most real deployments.

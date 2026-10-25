# Test plan

## Automated

- Validate protocol schema/fixture: `scripts/check.sh`.
- Desktop: `npm test` validates aspect-ratio/letterbox coordinate mapping.
- Android (once SDK is installed): `./gradlew testDebugUnitTest`; add state-machine, wire-size, pairing expiry and rate-limit tests before release.

## Manual device acceptance

1. Redmi Note 8 (Android 11) and Windows laptop are on the same private Wi-Fi. Start sharing, see persistent notification and mDNS device discovery.
2. Pair with an on-phone code; reject a bad code. Restart both apps and verify paired/offline/ready states are distinct.
3. Verify a real app screen—not a mock feed—arrives in the resizable viewer. Rotate phone twice; resize Windows window at 100% and 150% scaling.
4. Run 720p/30fps for 10 minutes; record FPS, latency, laptop CPU/RAM, phone heat/battery and disconnect behavior.
5. With accessibility disabled, verify view-only works and controls are clearly unavailable. Then enable it; test tap, swipe, scroll, Back/Home/Recents. Test a normal field and note secure-field failure.
6. Revoke pairing, stop from notification, deny projection, toggle Wi-Fi, background Android app, enable battery optimisation, and block multicast. Test manual-IP fallback and Windows Firewall prompt.
7. Confirm secure/DRM content is never bypassed and no stream starts without consent.

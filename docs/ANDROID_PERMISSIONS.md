# Android permissions and limits

`INTERNET`, Wi-Fi/network state: required for local discovery and transport. `POST_NOTIFICATIONS`: shows the persistent sharing control. `FOREGROUND_SERVICE_MEDIA_PROJECTION`: runs the consent-bound sharing session. No contacts, storage, camera, microphone, location, SMS, clipboard, or broad package permissions are requested.

Screen sharing requires Android's system consent dialog. Android may require fresh consent after a stopped session. Protected content, secure windows, lock screen, password and permission dialogs can be black or unresponsive by design.

The optional Accessibility Service is clearly labelled and only dispatches requested gestures/global navigation. It cannot reliably write arbitrary text into every app or secure field. Android or the user can disable it at any time; view-only streaming continues.

# Architecture

The LAN MVP has no account or cloud server. Android advertises `_android-mirror._tcp` over NSD/mDNS on TCP 38475. Windows resolves that record using `mdns-sd`; manual `IP[:port]` is the fallback for networks that block multicast.

The phone is the local signalling host. After a user-visible short-code pairing request, the apps exchange SDP and ICE over the LAN WebSocket. Android captures with `MediaProjection` through WebRTC's `ScreenCapturerAndroid`; WebRTC selects its hardware H.264 encoder when available and encrypts media with DTLS-SRTP. The Windows WebView2 Chromium engine creates the peer connection and decodes the video.

Control is sent as normalized coordinates over the authenticated control channel and translated to Android display pixels. The accessibility service is purposely configured not to read window contents; it only dispatches explicit gestures and global navigation operations during active sharing.

## Remote path

`relay/server.mjs` is a self-hostable, rate-limited WebSocket signalling relay. A phone registers a transient online presence; a desktop asks to connect to that device; the server routes only an approved session's signalling and control envelopes. It never receives WebRTC screen media when a direct peer connection works. A TURN server remains necessary for restrictive networks; encrypted media then crosses that TURN relay and consumes its bandwidth.

The relay is not public-by-default. It requires HTTPS/WSS behind a reverse proxy, a valid domain, server-side authentication, TURN credentials, secret storage and abuse controls before deployment. The relay source and validation test now exist, but Android/Windows remote-endpoint configuration and secure remote pairing do not; it is not a completed user feature.

## Current implementation boundary

The source uses a WebSocket signalling channel and confirms short-code pairing, but its production mutual-key authentication, TLS certificate provisioning, request replay window, pairing approval UI, drag state tracking, and secure Windows key storage are still outstanding. They must be completed before calling the pairing layer production-secure. WebRTC media remains encrypted in transit.

## Why WebRTC

It provides Android's supported screen-capture integration, H.264 negotiation, jitter buffering, adaptation and encrypted real-time media without requiring a custom H.264 framing/decoder pipeline. Chromium/WebView2 supports receiving it on Windows; the target-machine validation is explicitly listed in the test plan.

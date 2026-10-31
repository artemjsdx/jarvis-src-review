# TASK adb2 — ADB pairing interop with the real adb client (D-02, D-03)

Read first: `docs/TASK_ADB.md` (original contract), `docs/AGENT_BRIEF.md` (Wave 2 rules), `docs/BACKLOG.md` section D. Worktree/branch: `adb2`. You do NOT need the phone for this task (JVM only).

## State when you start
* The wire packet types were wrong (SPAKE2_MSG must be 0, PEER_INFO 1) and are fixed in your base commit. The relay header is now read unbuffered.
* Harness: `app/src/test/java/app/jarvis/core/adb/EkmPairingHarness.java` + `FakeAdbdEkmProxy.js` (Node 26 TLS 1.3 terminator exports the RFC 8446 keying material; Java runs the pairing core behind it) and `AdbInteropTest` against the REAL Termux `adb` 35.0.2 (private adb server port per test class).
* Current failure of `AdbInteropTest.realAdbPairConnectShell`: SPAKE2 messages are exchanged, then the Java side cannot decrypt the client's PeerInfo (`PeerInfo decrypt failed (len=-1)`), so the derived AES key differs from BoringSSL's.
* `AdbClientTest.shellCancel` and `AdbClientTest.pairConnectShellV2_fullFlow` fail (unfinished refactor of the earlier agent: it removed the BouncyCastle TLS1.2 test bootstrap).

## Goal
Make the whole `app.jarvis.core.adb` test package green, with `AdbInteropTest` proving that REAL `adb pair` (right code) succeeds against our Java server, a wrong code fails, an unknown key is rejected on connect, and `adb shell` works over the TLS transport.

## Where to look for the mismatch (verify each against the primary source, do not guess)
AOSP `packages/modules/adb/pairing_auth/pairing_auth.cpp` + `aes_128_gcm.cpp` + `pairing_connection/pairing_connection.cpp`, BoringSSL `crypto/curve25519/spake25519.c`. Sources are fetchable with curl/webfetch
(try `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_auth/pairing_auth.cpp?format=TEXT` (base64) and `https://raw.githubusercontent.com/google/boringssl/master/crypto/curve25519/spake25519.c`).
Checklist: SPAKE2 names (`"adb pair client\0"`/`"adb pair server\0"` incl. trailing NUL, sizeof), password = pairing code bytes + 64-byte exported keying material (label `"adb-label\0"`, no context), password hashing/reduction (`w`), M/N constants and their encoding, the transcript hash inputs and length prefixes,
key derivation (HKDF-SHA256, info string `"adb pairing_auth aes-128-gcm key"`, 16-byte key), GCM nonce layout (per-direction little-endian counter), packet header (version 1, type, 4-byte big-endian payload length), PeerInfo layout (1 + 8191 bytes, type 0 = ADB_RSA_PUB_KEY), role assignment (client = Alice).
Write a **known-answer test** for SPAKE2 using vectors from BoringSSL's tests if available, so the next regression is caught without Node and adb.

## Rules
Owned: `app/src/main/java/app/jarvis/core/adb/**`, `app/src/test/java/app/jarvis/core/adb/**`, `app/src/debug/java/app/jarvis/dev/checks/adb/**`. Production code must not keep test hooks you can avoid; the existing `PairingPeer.clientRelayPort` hook may stay but must be documented as test-only.
Do not change the public API contract (`AdbApi`, `AdbClient` public surface). Tests that cannot hang: every external process has a timeout; never use the global adb server (5037).
Deliver `docs/ADB_NOTES.md` (what was wrong, how verified, what remains unverified: Conscrypt's TLS exporter on the phone and the real adbd flow need a live pairing code, which the owner provides later).
Verification: `bash tools/run-tests.sh app.jarvis.core.adb.AdbInteropTest app.jarvis.core.adb.AdbClientTest` plus every other test class in that package, run 3 times in a row (no flakiness).

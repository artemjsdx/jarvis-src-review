# ADB pairing / connect — what was wrong, how it was verified (task adb2, D-02/D-03)

## Result in one paragraph
The REAL `adb pair` client (Termux adb 35.0.2, BoringSSL) now pairs with our Java pairing core, a wrong code fails, an unknown
key is rejected on connect, and `adb shell` works over our TLS transport (`AdbInteropTest`, plus 50 more tests in `app.jarvis.core.adb`).
**SPAKE2, password hashing, transcript, HKDF and GCM nonces were already correct.** The remaining interop failure was a
test-server bug (below); three production bugs and several test races were found on the way.

## What was actually wrong
1. **FakeAdbd answered the wrong PeerInfo type** (the symptom the task described as "cannot decrypt PeerInfo" was a stale
   `lastError` from the previous test, the wrong-code test; the Java side decrypted the real adb's packet fine).
   Real adbd replies with `PeerInfoType::ADB_DEVICE_GUID = 1` and a GUID string (`adb-<serial>-<rand>`), not an RSA key.
   Host adb (`client/adb_wifi.cpp`) then prints `Failed: Successfully paired but server returned unknown response=` for any
   other type, and the CLI shows it as `error: protocol fault (couldn't read status message): Success`. Found by talking to
   the adb server socket directly (`host:pair:<code>:<host:port>` returns the full FAIL text; the CLI hides it).
   Fix: `FakeAdbd.deviceGuidPeerInfo()`; `PairingPeer.TYPE_DEVICE_GUID`. Direction summary: host sends type 0 (RSA key line),
   device sends type 1 (GUID).
2. `AdbClient.shell(..., CancelToken)` ignored the per-call token (only the token given to `connect` was honoured), so
   cancelling a running shell never worked. Now the call token closes the socket and yields `CANCELLED`
   (`AdbConnection.shell(cmd, timeout, callToken)`).
3. `AdbConnection.parseModel` failed on the real banner (`device::ro.product.model=...`: the first `;` part carries the
   `device::` prefix) — status showed an empty device name when the model was the first property.
4. TLS 1.3 timing: the client finishes its handshake before the server judges the client certificate, so an unknown adb key
   surfaced either as `NOT_PAIRED` (handshake) or `NETWORK` (first read) depending on timing. The first read after the TLS
   upgrade now maps SSLException/EOF/reset to `NOT_PAIRED`.
5. Test-side races fixed: FakeAdbd shell v2 could drop the last stderr chunk (consumed the stderr EOF token while data was
   still queued); FakeAdbd closed the whole transport on any `CLSE` (real adb reuses one transport for many shells);
   interop assertions raced with the server thread (`lastPeerInfo`, `clientKeyRejected`) and `adb connect` returns before the
   transport is online (`wait-for-device` added); Node proxy processes outlived the JUnit JVM (shutdown hook).

## Verified against primary sources (checklist from the task)
Read from AOSP `pairing_auth.cpp`, `aes_128_gcm.cpp`, `pairing_connection.cpp`, BoringSSL `spake25519.cc` (master, fetched 2026-10-29):

| Item | Source says | Java |
|---|---|---|
| names | `sizeof("adb pair client")` = 16, i.e. incl. trailing NUL | `Spake2.CLIENT_NAME/SERVER_NAME` incl. `\0` |
| password | pairing code bytes ‖ 64 B exporter output, label `"adb-label\0"` (`sizeof`, NUL included), no context | `PairingPeer`; Node's `exportKeyingMaterial(64,'adb-label\0')` differs from the label without NUL (checked), empty context == no context in TLS 1.3 |
| w | `SHA512(password)`, `x25519_sc_reduce`, then add L/2L/4L so the low 3 bits are 0 (not reduced again); mask = w·M (Alice) / w·N (Bob) | `Ed25519.spakePasswordScalar`; BoringSSL's own tests show interop with/without this hack because the ephemeral scalar is ×8 |
| ephemeral | `reduce(64 random bytes)` ×8, P = x·B, msg = P + mask | `Spake2.start` |
| key | SHA512 over 8-byte-LE length-prefixed (alice name, bob name, alice msg, bob msg, dh, SHA512(password)) | `Spake2.processMessage` |
| AES key | HKDF-SHA256, no salt, info `"adb pairing_auth aes-128-gcm key"` (no NUL), 16 B | `AesGcm` |
| nonce | 12 B zero, first 8 B = per-direction LE counter (`memcpy` of `uint64_t`), empty AAD, 16 B tag | `AesGcm.nonce` (4 low bytes used; identical until 2^32 messages) |
| packet | `{version=1, type, be32 len}`; `SPAKE2_MSG=0`, `PEER_INFO=1`; payload ≤ 2×8192 | `PairingPeer` |
| PeerInfo | 8192 B: type byte + 8191 data | `PairingPeer.PEER_INFO_SIZE` |
| roles | client = Alice, server = Bob | yes |

Independent check: a from-scratch pure-Python implementation (RFC 8032 affine arithmetic + `hashlib`/`cryptography`) of the
above, fed with values captured during a real `adb pair`, reproduced the Java password hash, w, Bob message, shared key, and
decrypted the real adb client's 8208-byte AES-GCM PeerInfo (plaintext = the host's adbkey.pub line). That capture is now the
known-answer test.

## Tests (all in `app/src/test/java/app/jarvis/core/adb/`)
* `PairingKatTest` + `app/src/test/resources/adb_pairing_kat.properties` — no Node/adb needed:
  Bob vectors captured from a real adb (seed → message, key, and the real client's ciphertext opens); Alice vectors from the
  Python reference; wrong password does not open the capture; loopback exchange checks wire header bytes
  (`01 00 00 00 00 20`) and PeerInfo types in both directions. A mutation (dropping the NUL from the client name) makes two of
  these fail.
* `AdbInteropTest` — real adb 35.0.2 with a private adb server port (never 5037): right code → `Successfully paired ...
  [guid=...]`, our server received the host's key; wrong code → non-zero exit and server-side `WRONG_CODE`; connect with the
  paired key + `wait-for-device` + two shells (exit code 7 propagates); connect with an unknown key rejected server-side.
  Side effect: host adb writes the fake guid into `~/.android/adb_known_hosts.pb` (harmless).
* `AdbClientTest` — our client against FakeAdbd (pair via the Node/OpenSSL EKM terminator, connect/shell, cancel, timeout,
  persistence, legacy shell, classic AUTH).
* `EkmPairingHarness` / `FakeAdbdEkmProxy.js` — Node 26 TLS 1.3 terminator exports the RFC 8446 keying material, Java runs the
  pairing core behind it. `PairingPeer.clientRelayPort` is a **test-only hook** (documented in the source).
  `Spake2.withSeed` is a package-private KAT hook.

## NOT verified (and why)
* **TLS exporter on the phone.** `TlsSupport` calls `android.net.ssl.SSLSockets.exportKeyingMaterial` (API 31+) or bundled
  Conscrypt. The JVM here has no TLS 1.3 exporter, so production `PairingPeer.client(...)` (real TLS socket → exporter) was
  only exercised through the Node terminator, never end-to-end with Conscrypt. The label must go through with its trailing NUL
  and with `context == null`; if a pairing code is rejected on the phone while everything else matches, check this first.
* **Real adbd pairing flow** (device in "Pair device with pairing code", mDNS `_adb-tls-pairing._tcp`, adbd's own GUID
  PeerInfo, adbd-side key storage and the subsequent `_adb-tls-connect._tcp` connect) needs a live 6-digit code from the
  owner's phone. We only proved that the client half interoperates with BoringSSL's *client* and that our *server* half is
  accepted by the real adb *client*; the adbd server half (BoringSSL as Bob) was never run against our client.
* `AdbClient.pair` ignores the device PeerInfo (GUID) it receives; nothing is parsed or stored.
* mDNS discovery is not part of this package.

## Known flakiness
Under heavy host load (load average ~8 from parallel builds) or with two interop runs overlapping, the real adb server
occasionally stalled right after `Attempting to TLS handshake` (seen twice in ~40 runs: `wait-for-device` timeout, and the
"unknown key" server never being contacted within 15 s). Runs on a quiet bench: 6/6 full-package and 10/10 interop-only green.
`AdbInteropTest` now starts its own foreground private adb server (`server nodaemon`, port ≠ 5037) and appends its log tail to
those two failure messages so a stall can be told apart from a protocol regression.

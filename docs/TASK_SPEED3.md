# TASK speed3 — compressed STT upload, STT hedging, local wake as the default path (P-02, P-03)

Read first: `docs/AGENT_BRIEF.md` (Wave 2 + Wave 3 rules), `docs/SPEED.md` sections 4b and 6, `docs/WAKE.md`, `docs/BACKLOG.md` (P-02, P-03). Worktree/branch: `speed3`.

## Facts (from speed1's bench, Groq `whisper-large-v3-turbo`, 7.4 s phrase)
WAV 237 KB: 3.4–7.3 s; FLAC 97 KB: 2.6 s; MP3 32 kbit 30 KB: 1.5–2 s; **OGG/Opus 16 kbit 11 KB: ≈ 2.0 s (stable)**; AAC/ADTS is REJECTED (HTTP 400). The app currently uploads WAV. The wake mode sends EVERY sound (0.3–8.5 s) to Google Speech v2 in the cloud (≤ 10/min): delay, traffic, privacy.

## Deliver
1. **`AudioEncoder`** (Android, `app/src/main/java/app/jarvis/audio/`): `encode(short[] pcm16k, Format)` → bytes for OGG/Opus (`MediaCodec` `audio/opus` encoder + `MediaMuxer` `MUXER_OUTPUT_OGG`, API 29+; bitrate 16–24 kbit/s, mono 16 kHz), FLAC (`audio/flac` encoder, API 27+), WAV fallback; a pure-Java WAV writer stays. Runtime capability probe (`MediaCodecList`), cache the result. Hardening: encoder errors/timeouts → WAV fallback, never lose the utterance; encoding must run off the UI thread and take < 150 ms for 8 s of audio (measure on this phone's ART).
2. **Providers**: `GroqStt` and `ElevenLabsStt` (check which containers each accepts: Groq list above; read ElevenLabs docs/trial request) use the best accepted format; the choice is logged once per session (`stt: ogg/opus 11 KB`) and shown in the trace (`stt.bytes_uploaded` — fix the TODO from speed1: `SttResult` must carry the real uploaded byte count). `GoogleSpeechStt` already sends FLAC: verify and keep.
3. **Hedged STT**: setting `stt.hedgeMs` (default 3000): if the first provider in the chain has not answered by then, start the second in parallel, first non-empty result wins, the loser is cancelled (no double side effects: STT has none). JVM tests with fake providers (slow/fast/error/empty).
4. **Local wake default (P-03)**: inspect `wake/LocalStt`, `WakePack` (Vosk small ru model, 46 MB, sha256 pinned), `WakeController`/`WakeMatcher`. Make a clear `WakeEngine.pick()`: local Vosk when the model is installed and loads, else cloud; the cloud path for wake is rate-limited and only used when local is unavailable. Run the existing `WakeCheck` on this phone's ART (download the model with `WakePack` into the debug dir, verify sha256) and report: model load time, RTF (real-time factor) on recorded phrases from `$TMPDIR` samples, recognition of "Джарвис, включи музыку" variants, false-positive rate on 10 minutes of non-wake speech you synthesise with `edge-tts`/gTTS (list what you used). Wake latency target: wake word detected < 700 ms after it is spoken (state what you achieved).
5. **Settings keys** (no UI): `stt.format` (auto|opus|flac|wav), `stt.hedgeMs`, `wake.engine` (auto|local|cloud) documented in `docs/SPEED_SETTINGS.md` (append; `speed2` writes the same file: keep sections separate) for `set1` to wire.
6. Update `docs/SPEED.md` section 9 with measured numbers (bench, honest labels).

## Owned paths
`app/src/main/java/app/jarvis/audio/**` (new), `core/voice/*Stt*.java`, `core/audio/**`, `wake/**`, `rt/SttHub.java`, `rt/VoiceController.java` (only STT/wake selection), tests, `tools/bench/**` (additive), docs above.

## Acceptance
JVM tests green; ART check output for the encoder and the wake engine pasted in the report; a live Groq STT smoke (env `JV_LIVE=1`, one short phrase, ≤ 0.01 USD) showing the Opus request succeeds and the transcript equals the WAV one.

# TASK speed1 — latency tracing, speech-text cleaning, Edge TTS connection reuse, bench numbers (P-01, P-05, P-09, inputs for P-02..P-04)

Read first: `docs/BACKLOG.md` section P, `docs/AGENT_BRIEF.md` (Wave 2 rules), `NOTES.md` (voice facts). Worktree/branch: `speed1`. Device variant: `app.jarvis.speed1` (only if you need the phone; no sound, no mic).

Why: the owner finds the voice chain slower than Yandex Alice (end of speech → text → LLM → TTS → playback), and the TTS read "..." for 5–10 s. First we must MEASURE, then fix what the numbers show.

## Owned paths
New: `app/src/main/java/app/jarvis/core/trace/**`, `app/src/main/java/app/jarvis/core/voice/SpeechText.java`, `app/src/test/java/app/jarvis/core/trace/**`, `app/src/test/java/app/jarvis/core/voice/SpeechTextTest.java`, `tools/bench/**`, `docs/SPEED.md`.
Edits (minimal hooks, will be merged with other branches): `core/voice/EdgeTts.java` (+ `WebSocketClient` if needed), `rt/SpeechService.java`, `rt/VoiceController.java`, `rt/SttHub.java`, `rt/AgentRunner.java` (span marks only), `core/agent/SpeechStreamer.java`. Do not touch UI classes.

## 1. Turn tracing (P-01)
`app.jarvis.core.trace.TurnTrace`: a monotonic-clock record per voice/text turn with named marks and derived spans:
`wake_detected`, `speech_end`, `stt_start`, `stt_final` (provider, audio ms, bytes uploaded), `llm_request`, `llm_first_token`, `llm_done` (model, provider, tokens in/out, prompt chars), `tts_request` (provider, chars), `tts_first_audio`, `play_start`, `play_end`,
plus tool call spans. `TraceStore` keeps the last 100 turns in memory (and appends one compact JSON line per turn to `filesDir/trace/turns.jsonl`, capped at 2 MiB). API for the MCP bridge: `TraceStore.recent(int n)` → list of JSON objects (document the schema in docs/SPEED.md).
At the end of each turn write ONE journal line (category `perf`): e.g. `Ход 2,3 с до звука: STT 0,9 | LLM 0,7 | TTS 0,5` with the full breakdown in the entry details. Pure Java core + thin hooks; thread-safe; no allocation storms.
Hooks: find where each stage really starts/ends (VoiceController/SttHub/AgentRunner/SpeechService, the TTS provider chain, the player). If a stage cannot be marked precisely, say so in the report instead of faking a mark.

## 2. Speech text cleaning (P-05, P-09)
`SpeechText.clean(String)` applied to every TTS request before the provider (and before cache lookup, so cache keys use cleaned text): "…" and runs of dots → a single comma-like pause (or removed at sentence ends), markdown (`*`, `_`, backticks, headers, bullets, links → link text only), code blocks dropped with a short spoken marker only if configured, emojis/symbols removed, raw URLs → "ссылка", repeated punctuation collapsed, whitespace normalized.
A chunk with no letters/digits after cleaning is NOT sent to TTS at all. Keep Russian text intact (digits stay digits). Table-driven JUnit tests (at least 40 cases incl. the "..." / "…" / ". . ." cases and a long mixed markdown reply).

## 3. Edge TTS connection reuse (P-05)
Today every phrase opens a new TLS+WebSocket (`EdgeTts.connect`). Measure first (`tools/bench/edge_reuse.py`: N=10 short phrases, fresh connection each time vs one connection reused for sequential requests: time to first audio frame, total). If the service accepts several `speak` requests per connection, implement a small keep-alive pool (idle timeout < the service's, reconnect on error, cancel-safe, thread-safe) and prewarm hook. If it does not, document what was tried and what the service answered.
Verify playback starts on the first audio frames rather than after the whole file (trace `tts_first_audio` vs `play_start` vs `tts_done`); report the gap.

## 4. Bench numbers for the next steps (no app changes; scripts in tools/bench, numbers in docs/SPEED.md marked "стенд (vivo)", with date/time and network type)
a) RTT and TCP+TLS connect time to: openrouter.ai, api.groq.com, api.elevenlabs.io, speech.platform.bing.com (Edge), www.google.com (speech API), api.deepseek.com. Upload throughput (use a Groq/OpenRouter endpoint that accepts a body, or httpbin-like host if reachable).
b) For the sample phrase in `$TMPDIR/svet16k.pcm`: payload sizes and upload+transcribe time to Groq Whisper for WAV16k, FLAC, MP3 and OGG/Opus (ffmpeg is installed; Opus at 16/24 kbps). Report which formats Groq accepts.
c) LLM time-to-first-token through OpenRouter for `deepseek/deepseek-v4.1-flash` (or the cheapest DeepSeek Flash id from /models): same 3 KB prompt repeated 5 times (cache effect) vs a prompt whose first line changes each time; also with a 1,5 KB tool schema block. Max 30 calls, tiny outputs.
d) Conclusions: ranked list of where the seconds go, and the recommended order of fixes with expected savings.

## Acceptance
JVM tests green (`bash tools/run-tests.sh app.jarvis.core.trace.* app.jarvis.core.voice.SpeechTextTest` + the voice package), debug build OK, docs/SPEED.md contains real numbers, report states what was not verified (e.g. real microphone path).

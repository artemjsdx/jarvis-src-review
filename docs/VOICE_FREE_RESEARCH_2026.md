# Jarvis — free-first voice engines research (2026-11-02)

Scope: Russian-first Android voice assistant. **Do not treat a signup credit as perpetual free use.**
"Implemented" means a code path exists in the review branch, **not** that the provider was verified against live user credentials or on the vivo I2407.

## Speech recognition / STT

| Candidate | Free arrangement | Russian | Integration route | Jarvis status |
| --- | --- | --- | --- | --- |
| Vosk Russian small | Open-source local model; no request fees | Yes | Existing Android local model and wake matcher | Implemented; local model must be installed |
| Groq Whisper large-v3 / turbo | Recurring rate-limited free plan, account key | Yes | REST multipart | Implemented already |
| Cloudflare Workers AI Whisper v3 Turbo | Shared 10k Neurons/day recurring free quota | Yes | REST account ID/token, JSON base64 WAV | Added in review branch |
| Gemini Flash Audio understanding | Model-specific API free tier and rate limits | Yes | REST Interactions inline audio | Added in review branch; not dedicated Transcribe |
| Gemini 3.5 Transcribe | Dedicated ASR; free tier shown in Gemini API price table | Yes | REST Interactions + Files API, custom vocabulary | Candidate, not implemented (requires 2-step upload) |
| ElevenLabs Scribe | Free-plan usage restrictions, check current quotas | Yes | Existing REST multipart | Implemented already |
| OpenRouter audio transcription | Usage billed to credits | Yes via Whisper | Existing REST API | Implemented, **not free**; previously caused HTTP 402 |
| Google Speech unofficial endpoint | Key/quota restrictions and possible HTTP 403 | Yes | Existing legacy endpoint | Implemented, **unreliable**, do not default |
| Deepgram Nova-3 | One-time $200 signup credit, then paid | Check language/model | Official REST/WebSocket API | Research only |
| AssemblyAI Universal | One-time signup credits, then paid | Verify active model's locale | REST API | Research only |
| Speechmatics | One-time $100 signup credits | Yes | REST and streaming | Research only |
| Gladia Solaria | One-time €50 signup credits | Yes | REST/streaming | Research only |
| Azure Speech F0 | Limited recurring monthly allowance | Yes | Azure Speech SDK / REST | Research only |
| Cartesia Ink STT | Monthly shared STT/TTS credits | Verify Russian availability | API key | Research only |
| Scaleway Whisper | Startup free transcription minutes | Yes via Whisper | Official API | Research only |
| GigaAM-v3 e2e CTC int8 | Fully local, approx. 215 MiB for ONNX model | **Russian-specialized** | sherpa-onnx runtime; model download | Highest-priority offline candidate |
| Whisper Tiny/Base/Small | Open-source local weights; no request fees | Yes | whisper.cpp or sherpa-onnx + JNI | Offline candidate |
| Parakeet TDT 0.6B v3 | Open weights, no request fees | Yes (v3 language list) | sherpa-onnx | Offline candidate; relatively large |
| Qwen3-ASR 0.6B/1.7B | Open weights, no request fees | Yes | Qwen ONNX/JNI CPU | Candidate; heavier (~1.9 GB ONNX variant in one Android benchmark) |
| Android on-device SpeechRecognizer | No third-party STT API fees; locale/model depends on device | Device-dependent | Android SpeechRecognizer | Not directly drop-in: callback owns audio capture |
| FunASR / Paraformer | Open weights, no request fees | **Verify** Russian model; many focus on Chinese | Native ONNX service or external server | Research-only; do not label Russian-ready |
| SenseVoice Small | Open weights | **No standard Russian support** | sherpa-onnx | Not a Russian default |

## Voice synthesis / TTS

| Candidate | Free arrangement | Russian | Integration status |
| --- | --- | --- | --- |
| Android system TextToSpeech | Installed engine; no API fee; may be offline | Depends on downloaded voice | Added in review branch |
| Microsoft Edge Read Aloud | Unofficial keyless endpoint with unstable access | Yes | Existing |
| Google Translate TTS | Unofficial keyless endpoint, rate limits | Yes | Existing |
| Silero Space | Public model/demo can throttle | Yes | Existing |
| F5 Space | Public demo availability not guaranteed | Voice/model-dependent | Existing optional |
| ElevenLabs TTS | Recurring limited free quota | Yes | Existing |
| Gemini 3.8 Flash / Flash-Lite TTS | Gemini API free tier varies by model/region | Yes (verify voices) | Research only; needs audio output protocol integration |
| Google Cloud Standard / WaveNet | Free recurring character allowance but **billing required** | Yes | Research only |
| Microsoft Azure neural TTS | Speech free-tier quotas, setup required | Yes | Research only |
| RHVoice | Local open-source, Android-compatible engine | Yes | Can supply AndroidSystemTts once installed |
| Piper | Local open-source voices | Yes, Russian voice models exist | Could integrate via sherpa-onnx or Android system engine |
| Qwen3-TTS 0.6B/1.7B | Local open weights, compute required | Yes | Candidate for remote PC/Termux worker |
| Chatterbox Multilingual | Local open weights, compute required | Yes | Candidate for remote compute |
| Kokoro-82M | Local open weights; language support must be verified | Do **not** assume Russian | Potential Android model, not default |
| Cartesia Sonic | Monthly free credits, service-side restrictions | Verify supported voices | Research only |
| Deepgram Aura-2 | One-time signup credits | Models/voices have language constraints | Research only |

## Recommended architecture

1. Wake word: **local Vosk** for "Джарвис"; do not require cloud/credits just to activate.
2. Short command transcription: **Groq Whisper v3** (free recurring), **Cloudflare Whisper Turbo** as next fallback; Gemini Audio as optional text-quality comparator.
3. Better-context transcription: optional **Gemini 3.5 Transcribe** once the official Files API upload flow is implemented and tested.
4. Unlimited offline Russian transcription: sherpa-onnx **GigaAM-v3 INT8** after APK-size, RAM and latency profiling on the actual Android handset. Do not claim runtime support before adding native binaries and downloading model.
5. TTS: Edge as attractive keyless voice with Google Translate and **AndroidSystemTts** as independent fallback. Install RHVoice for a fully offline Russian system engine.
6. Keys: encrypted Secrets storage, each provider independently configured; STT chain and TTS chain must never reuse LLM credentials implicitly.
7. Diagnostics: display precise recording RMS, sample count, chosen AudioRecord source, STT provider id, HTTP status kind, playback stream, detected system volume and cue decode errors. Log without API secrets.

## Audio cues / known fixes

- Built-in crystal / water / other OGG files are present in app/assets/sounds; verified in a prior signed review APK as **uncompressed** assets.
- SoundPool USAGE_ASSISTANCE_SONIFICATION was changed to USAGE_MEDIA in the review branch so that previews are on the familiar media volume.
- StudioScreen now forwards file picker request codes 61/62 to SoundsPage; previously importing a custom cue through Voice Studio did not finish.
- The voice controller now warms cues much earlier, and Cues preloads every tiny built-in set; previously the first runtime event after switching a pack could be silent because loading is asynchronous.
- These are **code fixes only** until verified on vivo I2407. Hardware mic digital silence is a separate recording issue.
- User should verify preview button, actual recording start/stop, and silent mode/DND separately; preview intentionally ignores CuePolicy, while automatic cues respect it.

## Sources (official / implementation examples)

- Groq free-plan limits: https://console.groq.com/docs/rate-limits
- Cloudflare pricing: https://developers.cloudflare.com/workers-ai/platform/pricing/
- Cloudflare Whisper API: https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/
- Gemini Transcribe: https://ai.google.dev/gemini-api/docs/transcribe
- Gemini Audio: https://ai.google.dev/gemini-api/docs/audio
- Gemini pricing: https://ai.google.dev/gemini-api/docs/pricing
- Google Cloud TTS pricing: https://cloud.google.com/text-to-speech/pricing
- Android platform TTS: https://developer.android.com/reference/android/speech/tts/TextToSpeech
- GigaAM-v3 INT8 Android-ready model: https://huggingface.co/nocmt/GigaAM-v3-E2E-CTC-int8-ONNX
- Android offline ASR benchmark / implementations: https://github.com/voiceping-ai/android-offline-transcribe
- Open-source Android GigaAM: https://github.com/dedtsss/govorun-online-cleaner
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- Qwen3 TTS official: https://github.com/QwenLM/Qwen3-TTS
- Community Chinese comparison: https://cloud.tencent.cn/developer/article/2642961
- Free-tier survey (secondary, may lag changes): https://aifree.dev/learn/free-speech-to-text-api

Status at writing: design/research sources verified; live provider credentials and on-device sound tests have not been run.

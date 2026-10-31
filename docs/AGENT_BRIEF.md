# Brief for background engineering agents (shared rules)

Project: **Jarvis** — native Android voice assistant, built entirely on a phone in Termux. Read `NOTES.md` and `PLAN.md` first.

## Hard rules
1. **Language/stack:** Java, source level 8 (lambdas are fine: d8 desugars them). No Kotlin, no AndroidX, no third-party jars unless the task says so. Android code must run on minSdk 26 and be written against the API 36 `android.jar` (hidden APIs forbidden in `src/main`).
2. **Files:** work only inside the directories your task owns. *Contract files* (named in the task) are frozen: do not change their signatures. If you need something changed, implement your own helper and tell the lead in your final report.
3. **Never print, log, write to a file inside the repo, or commit any API key / token.** Live tests read keys from `~/jarvis/.cache/research/keys.py` (git-ignored; see below) or env vars and must mask them in output.
4. **No fake success.** If a real service cannot be reached from this network (Belarus egress; some services geo-block), say so in your report; do not mock a result and call it done. Unit tests may use local fake servers, but also run the real live check once and report numbers.
5. **Do not run git commands that change history.** Do not commit; the lead commits. Do not delete other people's files.
6. Be economical with free-tier quotas: ElevenLabs free = 10 000 characters/month total for the user. Do **at most ~10 short TTS calls** with it in total (keep phrases < 40 chars). OpenRouter / Groq: reasonable use.
7. Everything user-facing is **Russian**. Comments and identifiers English.
8. Keep classes small and documented where behavior is non-obvious. Prefer correctness and clear errors over cleverness. Errors must carry a user-safe Russian message and a technical string.

## Tooling (all run on the phone)
* `bash build.sh debug` — builds the APK (~15–60 s; phone may be loaded). `bash tools/setup.sh` is already done.
* `bash tools/run-tests.sh [fully.qualified.TestClass ...]` — JVM tests of the pure-Java core `app/src/main/java/app/jarvis/core/**` against `app/src/test/java/**` (JUnit 4, real sockets on localhost, org.json from `.cache/testlibs`). Uses OpenJDK 21, so tests may use `com.sun.net.httpserver`.
* `bash tools/art-apk.sh <MainClass> [args]` — builds debug APK and runs a `public static void main` class on the phone's **real Android runtime (ART)** through `app_process`. Put such mains in `app/src/debug/java/app/jarvis/dev/checks/` (debug-only, not shipped). Wrap main in try/catch and `printStackTrace(System.out)`: an uncaught exception only reaches logcat and the process is SIGKILLed silently. `adb logcat` is not needed: `logcat -d` works from Termux for own-process errors. Real `android.media.MediaCodec`, `AudioTrack`, `SQLiteDatabase`, `org.json`, `HttpURLConnection` (Android's own stack) are available there; there is no `Context` of our app and no UI session (see `app/src/debug/java/app/jarvis/dev/PreviewHost.java` for how views are rendered headlessly).
* `bash tools/preview.sh [SceneClass...]` — renders UI scenes from `app/src/debug/java/app/jarvis/dev/scenes/*Scenes.java` to `build/preview/*.png`; look at them with your image-reading tool.
* Python 3 with PIL/numpy/requests/websockets, `ffmpeg`/`ffprobe`, `curl`, `adb` (android-tools client) are installed. `javap`, `keytool` too.
* Keys for live tests: `python3 -c "import sys; sys.path.insert(0,'/data/data/com.termux/files/home/jarvis/.cache/research'); import keys; print(keys.eleven()[0])"` (also `keys.groq()`, `keys.deepseek()`, `keys.inception()`); OpenRouter key is in `~/.local/share/opencode/auth.json` → `["openrouter"]["key"]`. Pass them to Java via environment variables, never on a command line that is echoed into reports.
* Samples: `$TMPDIR` (= `/data/data/com.termux/files/usr/tmp`) holds `edge_ru-RU-SvetlanaNeural.mp3` (Russian speech "Привет! Я Джарвис, ваш голосовой помощник. Доброе утро."), `svet16k.pcm` (same, raw 16 kHz mono s16le), `svet16k.flac`, `full.aac`, `clip3s.aac`, several `edgev_*.mp3`, `silero_*.wav` (48 kHz), `el_*.mp3`/`.pcm` (ElevenLabs). Copy what you need into `app/src/test/resources/` (small files only).
* Network reality: this Termux host has very slow **upload** (~7 KB/s) and 0.5–2 s RTT; uploads of 100+ KB take 10–30 s here. That is a property of this test bench, not of the user's phone: report timings as "bench" numbers.

## Reporting
Finish with a concise report (max 40 lines): what you built (files), how you verified it (commands + results), measured numbers, what is NOT verified and exactly why, known limitations. Honesty over polish.

## Wave 2 rules (2026-10-29, supersede the older ones where they conflict)

* **Your own git worktree and branch.** The lead creates `~/jarvis-wt/<name>` on branch `<name>` (with `.cache` symlinked). Work ONLY there; never edit `~/jarvis` itself.
  You MAY commit on your own branch (small, meaningful commits; message in English). Never push, never touch other branches, never rewrite history.
* **Registry.** Requirements and acceptance live in `docs/BACKLOG.md` (IDs); the MCP bridge spec in `docs/MCP.md`. Your task file is `docs/TASK_<name>.md`.
  Do not edit BACKLOG.md; list the IDs you closed in your report (the lead updates the registry).
* **Side-by-side device builds.** Build with `JV_APP_ID=app.jarvis.<name> JV_APK_OUT=$PWD/build/outputs/<name>.apk bash build.sh debug`
  (package `app.jarvis.<name>`, label "Jarvis [<name>]", own data and ports). The default `build/outputs/jarvis-*.apk` path is shared: never install from it.
* **The phone** (the owner's real vivo I2407, he is asleep but it is his daily phone): `export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569`
  (`JV_DEVICE=$D` for tools/dev-*.sh; `JV_PKG=app.jarvis.<name>` so the focus guard watches YOUR package). Rules:
  1. Touch only your own package `app.jarvis.<name>`. Never the owner's `app.jarvis`, never `ai.jarvis.app`, never any other app or system setting. Read-only `dumpsys`/`getprop` are fine.
  2. No sound out of the speaker and no microphone use. Test TTS to files; use injected audio instead of the mic.
  3. Anything that brings your app to the foreground (`am start`, screenshots, taps) runs under `bash tools/with-screen.sh <cmd>` (lock shared by all agents); keep it short.
     Use `tools/dev-shot.sh|dev-ui.sh|dev-tap.sh` (they refuse to act when your package is not in focus). Never send blind taps.
  4. The adb connection is kept alive by a watchdog; do not run `adb kill-server`, `adb disconnect`, `adb pair`, `pkill -f adb`. If `adb devices` shows nothing, wait 30 s and retry.
  5. Uninstall your variant when you finish: `adb -s $D uninstall app.jarvis.<name>`.
* **Process hygiene.** Never use `pkill -f <pattern>` (it can match your own shell). Start long jobs with `setsid nohup … &` and poll their log files.
  Tool calls have a ~2 min limit: run builds/tests in the background and poll.
* **Money.** Live LLM calls are metered on the owner's OpenRouter key: use cheap models for experiments (`inception/mercury-3`, `deepseek/deepseek-v4.1-flash`), keep total spend of your task under 1 USD.
* **Secrets.** Same rule as before: keys are read from `~/jarvis/.cache/research/keys.py` / `~/.local/share/opencode/auth.json` at run time and never written to the repo, logs or reports.
* **Done means verified.** Run the JVM tests you touch, build the APK, and when the task says so verify on the phone with screenshots or MCP. State what was NOT verified.
* **Environment quirks of farm agents.** Your default `HOME` is the old proot home, not Termux's. Anything that reads the adb host key (`~/.android/adbkey`) or the git identity must be run with
  `HOME=/data/data/com.termux/files/home` (for example the ADB interop tests). Commit with `git -c user.name=agent-<name> -c user.email=agent@localhost commit ...`.

## Wave 3 rules (2026-10-29: the owner is travelling on mobile data, ADB to the phone is OFF for now)
* **Check the phone first**: `export ANDROID_ADB_SERVER_PORT=5038; adb devices`. If the phone is not listed, do NOT wait and do NOT try to pair/connect (you cannot). Do everything that needs no phone:
  JVM tests, builds (`JV_APP_ID=app.jarvis.<name> ... build.sh debug`), **UI previews through `tools/preview.sh` (renders on this very phone's ART via app_process, no ADB needed)**, ART checks through `tools/art-run.sh` with a hand-built classpath.
  Whatever really needs the phone (windows with hardware blur, microphone, notifications, real taps), write down as exact numbered steps in `docs/DEVICE_CHECKLIST_<name>.md` (what to run, what to look at, expected result) and list it under "NOT verified" in your report. Never claim device verification you did not do.
* **Models and money.** Agents run on `anthropic/claude-haiku-5.5` (cheap) or `anthropic/claude-sonnet-5.5` (hard tasks). The OpenRouter balance is shared with the owner's other tools: keep your own spend small, avoid dumping huge files/logs into the context (use `head`, `grep -n`, ranges), do not re-read files you already know.
* **You may be interrupted** (network blips resume your session automatically). Commit after every finished step on your branch; start every resumed run with `git status && git log --oneline -8`.
* **Merge hygiene**: other agents edit other files in parallel; stay inside your owned paths. If you must touch a shared file (`Ui.java`, `Widgets.java`, `Jarvis.java`, `AndroidManifest.xml.in`, `Prompts.java`, `Settings.java`), make the change minimal, additive and isolated so a merge is trivial, and say so in the report.
* **No silent failures.** Run the tests you touched; if something fails for a reason outside your area, say so in the report instead of papering over it.

## Owner rules (mandatory for every agent, set 2026-10-30)
* ONE version of the app: 0.1.0, name "Jarvis". Never edit `version.properties`, never put a version into an APK name for the owner. The owner's only APK is `Jarvis.apk`.
* Never install side-by-side variants (`app.jarvis.<name>`, label "Jarvis [name]") on the owner's phone. UI and bridge checks run in the CI emulator (`tools/ci/gh.sh`) or in the headless ART preview.

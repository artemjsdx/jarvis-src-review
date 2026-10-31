# TASK log1 — journal technical details, crash capture, and journal screen fixes (L-01, L-04, L-05, S-18, parts of L-02/L-03)

Read first: `docs/AGENT_BRIEF.md` (Wave 2 rules), `docs/BACKLOG.md` (section LOG, S-18). Worktree/branch: `log1`. Device variant: `app.jarvis.log1`.

## What the owner said (translated)
* "The journal is great, lots of sections. But if possible, besides what the app *expects*, log **exact places — files and exact LINES where a failure happened**. More technical. Especially for unexpected errors."
* "The event window (system does not give microphone audio in the background without an allowed service, or the microphone is off): it is 90% opaque but I can see the 10% is not blurred" (glass1 delivers the blur surface; you only make the window use the common overlay factory).
* "The 'Распознано (wake, Google Speech)' entries: what are they for? (any sound is recognized so it catches the call word)" → they flood the journal; make them quiet by default.
* "The status 'Ожидание' on top: I hope it updates in real life."
* "Segment switch (All / Info / Warnings / Errors) should become a liquid-glass drop like the bottom dock" → glass1 builds `SegmentedGlass`; the lead wires it. Keep the filter row isolated in one method so replacing it is a 3-line change.
* "When I tried to take a screenshot in settings, Android said **you can't make a screenshot over this app**. What is that?" → S-18.

## Owned paths
`ui/screens/LogScreen.java`, `core/log/**`, `data/SqlJournal.java`, `JarvisApp.java` (crash handler only), `rt/Jarvis.java` ONLY for a `Throwable → journal` helper, tests, `docs/LOGGING.md`.

## Deliver
1. **Error site capture (L-01)**: `ErrorSite.from(Throwable)` (pure Java, JVM-tested): picks the root cause chain, the first frame in `app.jarvis.*` (class, method, file, line) and up to 12 relevant frames; a stable fingerprint (class+method+line). `Journal.e(cat, msg, Throwable)` stores `site` (`MainActivity.java:123 in onResume`) and the trimmed stack in the entry details; the journal list shows a monospace "Где: File.java:123" line and a collapsible "Технические детали" block (full stack, thread name, app version/rev, Android/SDK, free memory) with a Copy button. **Verify line numbers survive the release build** (`bash build.sh release`; inspect a deliberately thrown exception on ART: `tools/art-apk.sh`): if d8 release drops them, add the right flags (`--no-desugaring`-independent: `--release` keeps `LineNumberTable` unless a mapping/strip option is used; check) and document how stack lines map back.
2. **Global capture**: `Thread.setDefaultUncaughtExceptionHandler` (chain the previous one) writes `crash/last.txt` (existing) AND an ERROR journal row with the site; caught-and-swallowed exceptions in the core that currently only log a message get the Throwable attached where trivial (grep `Journal.e(`/`log(... ERROR` call sites in `rt/**` and `core/**`: update at least the 15 most important: agent loop, LLM client, TTS/STT providers, tools, DB, services). Also catch `OutOfMemoryError`-style fatal paths without allocating much.
3. **L-04**: wake/stt "Распознано (wake …)" and "Речь без обращения"/"Длинная речь пропущена" rows become level DEBUG (hidden unless the filter "Отладка" or search is used); keep counters in a single rolling summary row ("за 10 мин: 14 фрагментов, 1 обращение") updated in place.
4. **L-05**: replace the confusing sentence with a clear one + a "Что делать" action (open battery/background settings page of the app, or toggle the mic service) — the journal event detail window has an optional action button (`JournalAction`).
5. **Status text 'Ожидание'** (H-03 support): audit that the Home status reflects `AState` transitions (idle/listening/thinking/tool/speaking/error) and add a JVM test for the mapping function if it is pure; report mismatches you find.
6. **S-18**: reproduce on the phone (variant open on Settings): `adb shell dumpsys window windows | grep -iE "secure|FLAG_SECURE|DISABLE_SCREENSHOT"`; try `adb exec-out screencap -p` and the power+volume screenshot gesture is not available to you — explain what you can and cannot check; check the manifest/Window flags of every `Activity`/`Dialog`/`PopupWindow` we create. Conclude with evidence whether our app sets a secure flag (grep says no) and what else on vivo could produce the message (e.g. the wireless-debugging pairing code dialog or other system windows); write it in docs/LOGGING.md.
7. Journal screen polish that does not depend on the glass kit: list performance with 10k rows (windowed paging exists? keep), search highlight, copy/share unchanged.

## Verification
JVM tests (`ErrorSite`, journal round-trip with site, wake summary aggregation). On the phone (variant, `tools/with-screen.sh`): trigger a debug-only deliberate exception from a debug check, show the journal row + details screenshot with `File.java:line`; show the wake summary row. Report what is only preview-verified.

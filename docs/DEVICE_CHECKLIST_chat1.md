# Device checklist — chat1 (chat container, bubbles, action block)

Not verified on the phone (ADB was off during this run). Run in order on variant `app.jarvis.chat1`.
Build: `JV_APP_ID=app.jarvis.chat1 JV_APK_OUT=$PWD/build/outputs/chat1.apk bash build.sh debug`, then `adb -s $D install -r build/outputs/chat1.apk`.
Use `bash tools/with-screen.sh` for every step that brings the app to the front.

1. Bubble alignment (C-05, C-07). Seed the conversation (ChatSeedCheck or `run-as app.jarvis.chat1` sqlite push), open Agent.
   Expect: user bubbles touch the right padding edge, assistant bubbles the left, the model label ("DeepSeek", "deepseek/…") is not cut, the time is visible.
   Screenshot: `bash tools/dev-shot.sh chat1_bubbles.png`.

2. Action block collapsed (A-01, A-02). Send a request that calls 3 tools (for example "покажи файлы").
   Expect: one header per run, icons in a strip, newest on the right, summary "3 действия · … с", chevron down.
   While running: nine dots in the rightmost slot, blinking in a spiral; done: green check, failed: red cross.

3. Expand without jump (A-03, U-09). Scroll so a block is at the top, tap its header.
   Expect: the header row stays at the same screen position; the body opens with an animation (about 220 ms). Repeat with a sub-block above the viewport while a new message streams: the content in view does not move.
   Proof: screenshot before and after, compare pixel rows of unchanged content (`python3` PIL diff of the two screenshots, the rows below the change must match).

4. Sub-blocks (A-03). Tap one sub-block: args and result appear with copy button. Tap a second one: "свернуть всё" chip appears; tap it: all close.

5. Overflow (A-01). Trigger 7+ tool calls in one run: the strip shows 6 icons, the oldest slide out to the left.

6. Timers (A-04). The running summary time increases each 0.5 s; finished calls show their duration in ms/s.

7. Scroll-to-bottom button (C-08). Scroll up in a long chat: a glass circle appears bottom centre with a fade and scale; new messages arriving while scrolled up increment the badge; tapping it scrolls to the bottom and hides it.
   Known gap: the blurred backdrop is not attached yet (GLASS_HOOK in ScrollToBottomButton); the fill is the fallback glass.

8. Older history (U-09). With more than 60 items, scroll to the top: older rows load without a jump.

9. Attach sheet (C-09). Tap the paperclip: sheet with "Файлы" and "Галерея". "Файлы" opens the document picker; "Галерея" opens the photo picker (Android 13+) or the image gallery. Long-press the paperclip after one choice: it repeats the last choice without the sheet.

10. Typing indicator (streaming). Send a request, while the answer has no text yet: three dots pulse; when the first token arrives the dots are replaced by text.

11. Light theme (U-01 not done: switch needs restart). Check the same screens with the light palette if the device is set to light mode.

Expected screenshots to attach to the report: items 1, 2, 3 (before and after), 7, 9.

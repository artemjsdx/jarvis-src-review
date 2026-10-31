# Device checklist — sessions drawer (drawer1)

Run on the vivo I2407 with the variant build `app.jarvis.drawer1` (`JV_APP_ID=app.jarvis.drawer1 bash build.sh debug`,
APK installed with `adb -s $D install -r ...`). Touch only `app.jarvis.drawer1`. Anything that brings the app to the
foreground goes through `bash tools/with-screen.sh`. No microphone or speaker use except where a step says so.

Not verified yet (no ADB while the owner is travelling): everything below.

## 1. Open and close
1. Open the Agent tab. Tap ≡ at the top left. Expect: panel slides in from the left in about 280 ms, screen dims, rows
   appear with a short stagger (first 8 rows).
2. Tap the dimmed area. Expect: panel slides out in about 200 ms.
3. Open again, press the system Back button. Expect: panel closes, the app stays on the Agent tab.
4. Open, swipe the panel left. Expect: it follows the finger and closes when released past half way or on a fling.
5. Tap the session title in the top strip. Expect: same as ≡.

## 2. Edge swipe vs system back gesture (vivo gesture navigation)
6. From the left edge (within about 24 dp), drag right, clearly horizontal. Expect: panel follows the finger.
7. Note whether the system back gesture wins over the edge swipe. If the system takes it, the drawer opens only from ≡
   or the title. Record the result; a fix would be `View.setSystemGestureExclusionRects` for the left strip (API 29+),
   which trades away the back gesture in that strip.
8. Inside a code block that scrolls horizontally, drag sideways from its left edge. Expect: the code block scrolls, the
   drawer does not open unless the drag starts in the edge zone and is clearly horizontal.
9. Vertical scroll of the chat from the left edge. Expect: chat scrolls, drawer stays closed.

## 3. Touch slop
10. Tap a row with a small finger movement (under 16 dp). Expect: the session opens, no drag.
11. Start a drag and move mostly vertically. Expect: the list scrolls, no panel movement.

## 4. Search and keyboard
12. Tap the search field. Expect: keyboard appears, panel does not jump.
13. Type «план». Expect: results after about 150 ms, only matching sessions, section headers kept.
14. Type «zzzz». Expect: «Ничего не найдено» and the hint «Попробуйте другое слово».
15. Tap the ✕ in the field. Expect: text cleared, full list back, keyboard stays until dismissed.
16. Scroll the list with the keyboard open. Expect: no crash, rows remain tappable.

## 5. Sessions
17. Tap «+» in the panel. Expect: panel closes, a new empty chat is shown, keyboard appears in the input field.
18. Tap «+» again on the empty chat. Expect: same chat is reused (no second empty session).
19. Long-press a row. Expect: action sheet with Переименовать, Закрепить/Открепить, В архив/Из архива, Голосовой чат здесь,
    Удалить (red).
20. Rename to a long title (over 80 characters). Expect: title is clipped to 80 characters, top strip updates.
21. Pin a session. Expect: it moves to «Закреплённые» with the star.
22. Archive a session; tap ⋮ → «Показать архив». Expect: archived session appears in «Архив», toggle shows a check.
23. Tap ⋮ → «Показывать тестовые (MCP)». Expect: `mcp-…` sessions appear.

## 6. Delete and undo
24. Delete a non-active session. Expect: confirm dialog; after OK the row disappears and «Чат удалён  Отменить» bar appears
    at the bottom for 5 s.
25. Tap «Отменить» within 5 s. Expect: row returns, bar disappears, nothing deleted (check the session still opens).
26. Delete again and wait 6 s. Expect: bar hides, session gone after reopening the panel.
27. Delete the active session. Expect: after the window the app selects the newest other session (or creates one); the
    chat list updates without restart.

## 7. Voice chat in a session
28. Long-press another session → «Голосовой чат здесь». Expect: panel closes, that session becomes active in the top strip,
    microphone permission asked on first use. Only if the owner agrees: speak one phrase; the reply goes into that session.

## 8. Clear context
29. Tap the refresh icon in the top strip. Expect: divider line «Контекст очищен» across the chat, toast «Контекст очищен»,
    history above the divider still visible.
30. Same from ⋮ → «Очистить контекст текущего чата». Expect: same divider, only once per tap.

## 9. Layout on the phone
31. Status bar: panel header sits below the status bar, not under it.
32. Bottom dock: the floating dock draws over the bottom of the panel. Check the last row is still reachable by scrolling.
33. Dark and light themes: rows readable, active row accent visible (light: lavender fill).
34. Font scale 1.3 (system setting, read-only check first): titles ellipsize, no overlap with time/count.
35. Animations off (Developer options animator scale 0): drawer opens and closes without animation, undo bar still works.

## 10. Known gaps in the preview (must be checked here)
36. The action sheet panel itself is not visible in the headless preview (only its scrim). Open one on the phone and check
    the list layout and the red «Удалить» colour.
37. Undo bar is not in any preview scene. Check its position above the dock and its text on the phone.

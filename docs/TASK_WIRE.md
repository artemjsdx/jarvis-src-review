# TASK wire1 — wire the liquid-glass kit into the screens, finish the visual polish pass (L-02, L-03, C-08, U-05/U-06 integration, D-15)

Read first: `docs/AGENT_BRIEF.md` (Wave 3), `docs/STATE.md`, `docs/GLASS.md` (components written by glass1: `Glass`, `GlassHost`, `SegmentedGlass`, `LiquidIndicator`, `GlassLevel`; the GPU preview path works: real blur/lens appear in `tools/preview.sh` PNGs), `docs/UI_KIT.md` (kit1: tokens, `Motion`, `Ui.card`), owner's screenshots `docs/ref/owner-screens/`. Worktree/branch: `wire1`. Model: Haiku. Verify EVERYTHING by rendering previews and LOOKING at the PNGs.

## Owner's words (translated)
"Every element that has any transparency — even 90 % opaque — must blur the background behind it, like real Liquid Glass; in none of your elements is the background blurred. Fix it for all, including the scroll-down button and the journal event window. In the journal the All/Info/Warnings/Errors switch must be the same liquid-glass drop as the bottom dock."

## Deliver
1. **Journal filters** (`ui/screens/LogScreen.java`, the isolated `buildLevelFilter()` method): replace the segmented control by `SegmentedGlass` (items Все / Предупр. / Ошибки / Отладка as log1 defined them; keep `JournalFilter` semantics); the second row of category chips stays but gets the same spring "drop" selection (a second `SegmentedGlass` in scrollable mode, or `LiquidIndicator` behind a horizontal scroller; pick what glass1's API supports without hacks).
2. **Journal event window** (`LogScreen` detail sheet) and every dialog/sheet/toast/popup built through `OverlayHost`/`Dialogs`/`HelpPopup`/`ListSheet`/`AttachSheet`/`SessionsDrawer`: confirm each is drawn over the blurred backdrop (glass1 marks `OverlayHost` panels; find the ones that bypass it: `grep -rn "PopupWindow\|new Dialog\|setBackground(Ui.rect(" ui/`) and route them through `Glass.mark/background`. The drawer panel has a `// GLASS_HOOK`, the scroll button too.
3. **Scroll-to-bottom button** (`ui/chat/ScrollToBottomButton.java`, hook `// GLASS_HOOK`): use `Glass.floating(...)` so it blurs the chat text behind it; keep the unread badge readable.
4. **Dock and header**: verify the dock drop on the real screens in preview (home, agent, files, settings), dark and light; fix clipping/z-order issues you find (the dock must stay above the chat input; keyboard open state).
5. **Glass level setting**: add the `ui.glass` row (Авто / Полный / Упрощённый / Выкл + "?" help via `HelpPopup`) to `ui/settings/AppearancePage.java` (kit1 owns the file but is finished; keep the diff small) and make `GlassLevel` react live.
6. **Cleanup items from earlier agents**: delete the unused `ModelSheets.historySheet` + `ConvSink` (drawer1 note), remove `// GLASS_HOOK` markers you resolved, make sure `UiSourceLintTest` and the glass unit tests pass.
7. **Polish sweep with the GPU previews**: render every scene (`bash tools/preview.sh`, env `JV_PREVIEW_OUT=build/preview-wire`), build a contact sheet (PIL) of dark and light variants for: Home, Agent (chat + action blocks + drawer open), Files, Terminal, Voice tab, Journal, Settings list and 3 pages. Fix every visible defect you can name (clipping, overlap, missing borders, unreadable contrast, wrong icon, inconsistent paddings; use kit1 tokens). List what you fixed and what remains in the report with the PNG paths.
8. `docs/DEVICE_CHECKLIST_wire1.md`: what to look at on the real phone (frame pacing with glass on a 120 Hz screen, blur cost, both themes).

## Owned paths
`ui/screens/LogScreen.java`, `ui/chat/ScrollToBottomButton.java`, `ui/chat/drawer/**` (glass hook only), `ui/OverlayHost.java`, `ui/Dialogs.java`, `ui/HelpPopup.java`, `ui/settings/AppearancePage.java`, `ui/chat/ModelSheets.java` (deletions), and small fixes in any `ui/**` file for the polish sweep (state each in the report). Do not touch core/rt.

## Acceptance
Tests green (`HOME=/data/data/com.termux/files/home bash tools/run-tests.sh`), build OK, contact sheets saved under `build/preview-wire/` (and the two best ones copied to `docs/design/` as JPEG ≤ 300 KB each), honest list of what is only verifiable on the phone.

# TASK chat1 — chat container, bubbles, Replit-style action block (A-01..A-05, C-05, C-07, C-08 view, C-09, C-12, U-09)

Read first: `docs/AGENT_BRIEF.md` (Wave 2 rules), `docs/BACKLOG.md` (sections CHAT and ACT), the owner's words below. Worktree/branch: `chat1`. Device variant: `app.jarvis.chat1`.

## What the owner said (translated)
1. "In the Agent chat **my messages are shifted** (misaligned bubbles)."
2. "When I **expand an action block it jumps down**, and expanding/collapsing has **no animation** (also for the 'tools the agent used' block)."
3. "The scroll-to-bottom button is semi-transparent but the background is not blurred" (glass1 delivers `Glass.floating(...)`; you provide the button view and logic and a fallback look).
4. "**Compose the actions block like Replit**: one main block between messages; as actions happen, sub-blocks (today's cards) appear *inside it*. In the top block there is a row of **SVG icons** — the more actions the more icons accumulate up to a limit, then old ones leave and new ones come in. The **active (right-most) action icon is highlighted**; when it finishes it turns grey. While an action runs its icon is **nine dots in a 3x3 square blinking in a snake/spiral order**; when done: a green check, or a red cross if it failed. I can expand the top block and the sub-blocks fall out; I can expand any sub-block, or collapse everything at once. All with open/close **animations**. Keep the durations per action (timers)."
5. "In a short assistant message the **model label gets cut** (shows 'EepSeek' instead of 'DeepSeek')."
6. "The attach (paperclip) button: let me choose **file manager** or **gallery directly**."
7. Day labels ("yesterday") are good, keep them. Lots of room for the chat is good, keep it.

## Owned paths
`ui/chat/**`, `ui/screens/AgentScreen.java`, `ui/home/**` is NOT yours. New: `ui/chat/actions/**`, `ui/chat/list/**`. Core data classes `data/ChatItem.java` (additive fields only). The sessions drawer/new chat/clear context (C-01..C-04) is another task running in parallel: leave a clearly marked toolbar area and a `// SESSIONS_HOOK` comment, do not implement it.

## Deliver
1. **Chat container instead of `ListView`** (U-09): a `ScrollView`-based `ChatListView` (or `RecyclerView`-like own recycler if needed for long histories) that shows the last N items (default 60) and loads older ones on scroll-up without jumping; **scroll anchoring**: when an item above the viewport changes height (expand/collapse, streaming), the visible content does not move; stick-to-bottom only while the user is at the bottom; animated height changes (`ValueAnimator` on the item's measured height, `Ui.ms()` aware); item insert animation (fade + 8 dp rise); day separators kept; text selection and the long-press menu kept; streaming bubble updates cheaply (no full rebuild per token). Old `ChatAdapter` API consumers (`AgentScreen`, previews) must keep working or be updated by you.
2. **Bubbles** (C-05, C-07): rewrite the bubble view with a custom `onMeasure` (wrap-content width up to 78% of the screen, minimum width = width of the meta row incl. the full model label); user bubbles right-aligned with symmetric margins, assistant left-aligned; verify with the existing diagnostic scene `BubbleProbeScene` and new `ChatBubblesScene` (short/long/multiline/RTL-free Cyrillic, model label of different lengths, selectable text) — **the shift must be gone on the phone**, not only in the preview. Root-cause it first (state the cause in the report).
3. **Action block** (A-01..A-05): `ActionGroupView` = header strip + body.
   * Header: rounded glass card; left: icon strip (max 6 visible, newest on the right, older ones slide out left with fade); right: summary ("3 действия · 4,2 с", live timer while running) and a chevron. Tap toggles expansion with an animated height + chevron rotation.
   * Icons per tool: map `toolName` → vector drawable (reuse `res/drawable/ic_*.xml`; list missing ones in the report, kit1 owns adding new files: request them in docs/TASK_CHAT_ICON_REQUESTS.md as `tool_name -> lucide-name`).
   * `ActionDotsView` (3x3 dots, snake order, each dot fades 0.25→1→0.25 with a phase offset, 1.1 s loop, accent colour; static grey 3x3 when `Ui.reduceMotion`), then check/cross glyphs (green `P.ok`, red `P.err`), idle/finished icons grey (`P.text3`), active icon highlighted (`P.accent`, slightly larger).
   * Body: the existing per-call cards become compact sub-blocks (icon + title + duration + status, one line), each expandable to args + result in monospace with copy; "свернуть всё" chip when more than one is open. Sub-blocks appear with an insert animation as calls start.
   * Grouping rule: all consecutive tool items of one run (and the blank assistant turns between them) belong to one group; parallel calls (the agent loop now runs independent read-only calls concurrently) show several active icons at once.
   * Persisted `ChatItem` kinds stay as they are (TOOL items); the grouping is a view concern.
4. **Scroll-to-bottom button** (C-08): `ScrollToBottomButton` view (chevron-down in a glass circle, unread counter when new items arrived below, fade/scale in and out, 44 dp). Until glass1's `Glass.floating` exists on master use `Ui.glass*` factories; leave `// GLASS_HOOK` where the blur backdrop will be attached.
5. **Attach picker** (C-09): tap = bottom sheet with two big choices "Файлы" (document picker `ACTION_OPEN_DOCUMENT`) and "Галерея" (`ACTION_PICK` on `MediaStore.Images/Video` or Android 13+ photo picker `ACTION_PICK_IMAGES`), long-press on the paperclip opens the last used one directly. Result handling reuses the existing attach flow.
6. **Empty text** is never rendered as "…" for finished messages (the adapter already skips blank assistant turns; keep that behaviour in your container). A streaming placeholder is a small animated three-dot typing indicator, only while `streaming`.

## Verification
* Preview scenes (`tools/preview.sh ChatBubblesScene ChatActionsScene ...`): states idle / running 1 / running 3 parallel / done / failed / expanded / many actions (>6, icons overflow), in dark and light.
* On the phone (variant app.jarvis.chat1, `tools/with-screen.sh`, `tools/dev-*.sh`): create a conversation by sending text through the app (needs a key: the variant has none; use the debug-only seeding `ChatStore` directly from a debug check class `ChatSeedCheck` run with `am start` extras, or push a prepared sqlite via `run-as app.jarvis.chat1` — the variant is debuggable), screenshot: bubble alignment, action group collapsed/expanded, frames of the expand animation (screenrecord → ffmpeg frames), the "no jump on expand" proof (screenshot before/after expanding an item above the viewport: pixel rows of unchanged content identical).
* JVM tests for grouping logic and the anchor math (pure Java parts under `app/src/test/java/app/jarvis/ui/chat/`).
* Honest report: what is verified on the phone vs only in preview.

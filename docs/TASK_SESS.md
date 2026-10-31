# TASK sess1 — chat sessions backend, "new chat" / "clear context" / voice-in-context (C-01, C-02, C-04 backend, C-03 data layer)

Read first: `docs/AGENT_BRIEF.md` (Wave 2 + Wave 3 rules), `docs/BACKLOG.md` (CHAT section). Worktree/branch: `sess1`. UI of the drawer is a LATER task (the chat screen is being rebuilt by `chat1`): you deliver the data/runtime layer, tests, MCP-friendly API and settings, plus the minimal two buttons described below.

## Owner's words (translated)
"I **cannot clear the context. I cannot start a voice chat in the context of a particular chat. And I cannot start a NEW chat WITHOUT context.** We need **sessions** — some side menu with sessions."

## Facts about the current code (verify)
`data/Db.java` has tables `conv(id,title,created,updated,…)`, `item(seq, conv_id, kind, …)`, `run(id, conv_id, …)`; `ChatStore.history(conv, maxChars)` builds the model context; `rt/AgentRunner` stores items under `run.conv`; `Jarvis` knows the current conversation; the MCP branch (`mcp1`) adds a "session override" in `AgentRunner` (merge-compatible: keep a single obvious extension point, e.g. `Jarvis.submit(text, atts, source, convId)`).

## Owned paths
`data/ChatStore.java`, `data/Db.java` (additive migration only: new columns via `ALTER TABLE` guarded by `PRAGMA user_version`; never drop data), `data/ChatItem.java` (additive), new `rt/Sessions.java`, `rt/Jarvis.java` (session accessors only), `rt/AgentRunner.java` (conv selection only), `rt/VoiceController.java` (conv selection only), tests under `app/src/test/java/app/jarvis/data/**` and `core/**` if pure; ART check in `app/src/debug/java/app/jarvis/dev/checks/SessionsCheck.java` (like `DataCheck`: real SQLite on ART via `tools/art-apk.sh`, no phone needed).

## Deliver
1. **Schema/migration**: `conv` gets `pinned INTEGER`, `archived INTEGER`, `hidden INTEGER` (MCP test sessions), `last_preview TEXT`, `item_count INTEGER`, `context_from_seq INTEGER` (messages with `seq < context_from_seq` are visible in the chat but NOT sent to the model), `model_note TEXT`. Migration is idempotent and survives an existing DB with data (test with a DB created by the OLD schema: copy the old `CREATE TABLE`s into a test fixture).
2. **`Sessions` API** (thread-safe, event callbacks for UI): `create(title?)`, `list(query?, includeHidden, includeArchived)` with preview/time/count, `rename`, `pin`, `archive`, `delete` (with cascade to items/runs; deleting the active session activates the newest other or creates a fresh one), `active()`, `select(id)`, `autoTitle(id, firstUserText)` (first 40 chars, cleaned; later an LLM-made title hook, not required now), `clearContext(id)` (sets `context_from_seq` to the current max seq and adds a SYS item "Контекст очищен" so the chat shows a divider; history stays visible), `fork(id, fromSeq)` optional.
3. **Runtime**: `ChatStore.history()` honours `context_from_seq`; `AgentRunner`/`VoiceController` always use `Sessions.active()` unless a conv id is passed explicitly; `Jarvis.startVoiceChat(convId|new)` = select/create then `voice.startListening()`; the app restores the last active session on start (`ui.lastConv` setting) and never loses messages on a crash (existing recovery logic must keep working: run the existing DataCheck).
4. **Direct-command and memory interplay**: `DirectCommands` replies are stored in the active session; "очисти контекст"/"новый чат" are recognised as direct commands (add patterns in `DirectCommands` + tests) and call the new API.
5. **Minimal UI hook (no screen rework)**: in `AgentScreen`'s top bar area add two icon buttons "Новый чат" and "Очистить контекст" (with a confirm toast "Контекст очищен") ONLY if it can be done in ≤ 25 lines inside a clearly marked `// SESSIONS_HOOK` block; otherwise expose the API and document the exact integration steps in docs/SESSIONS.md for the chat1 agent (it owns the screen).
6. **Tests**: migration from the old schema, history window after clearContext (including tool-call pairing at the boundary: never start the model context with a tool result without its call), delete cascade, pin/archive ordering, active-session fallback, preview/count maintenance, concurrency (two threads adding items), `DirectCommands` patterns. ART DataCheck-style run proving real SQLite behaviour on the phone's runtime.
7. `docs/SESSIONS.md`: schema, API, behaviours, MCP tool mapping for `chat_sessions/chat_new/chat_clear_context` (so mcp1/lead can wire them).

## Acceptance
JVM tests green (no new failures in the suite), debug build OK, ART `SessionsCheck` output pasted in the report, IDs closed: C-01, C-02, C-04 (backend), C-03 (data layer).

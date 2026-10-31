# Chat sessions — data layer and API (C-01, C-02, C-03 data, C-04 backend)

## Schema (`data/Db.java`, `PRAGMA user_version = 2`)
Migration v1 → v2 is additive: `ALTER TABLE conv ADD COLUMN …` for every new column, then backfill. Nothing is dropped.

| column | meaning |
|---|---|
| `pinned` INTEGER DEFAULT 0 | pinned sessions sort first |
| `archived` INTEGER DEFAULT 0 | hidden from the default list, still searchable with `includeArchived` |
| `hidden` INTEGER DEFAULT 0 | MCP test sessions (title starts with `mcp-`); hidden unless `includeHidden` |
| `last_preview` TEXT | newest non-empty USER/ASSISTANT text, first 120 chars, newlines → spaces |
| `item_count` INTEGER DEFAULT 0 | number of items; maintained by `ChatStore.add` in the same transaction |
| `context_from_seq` INTEGER DEFAULT 0 | items with `seq < context_from_seq` are shown in the chat but NOT sent to the model |
| `model_note` TEXT | reserved (not written yet) |

Backfill on migration: `item_count`, `last_preview`, `hidden` for existing `mcp-*` rows. Tested on ART with a v1 fixture (`SessionsCheck`).

## Runtime (`rt/Sessions.java`, `data/ChatStore.java`)
* **Active session**: `ChatStore.currentConversation()` returns the active id; if it is unset or deleted it falls back to the newest visible, non-archived session, creating "Новый диалог" when none exists. The choice is persisted in `ui.lastConv` and restored on start (`Sessions` constructor).
* **Text and voice input** go to `Sessions.active()` (`AgentRunner.runOnce`). An explicit id still wins: `Jarvis.submit(text, atts, source, convId)` (0 = active). The MCP session override uses this path unchanged.
* **Model context**: `ChatStore.history(conv, maxChars)` reads only items with `seq >= context_from_seq`, then `ContextBudget.repairPairing` drops orphan tool results, so the context never starts with a result whose call was cut off.
* **Auto title**: the first user message sets the title (first 40 code points, markdown stripped, one line) only while the title is still "Новый диалог".

## API (`rt/Sessions.java`, thread-safe, listeners get `onSessionsChanged(activeId)` on the executor given at construction — `Jarvis` passes the main thread)
| method | behaviour |
|---|---|
| `active()` | active id |
| `create(title?)` | new session, becomes active |
| `newChat()` | reuses the active session if it has no items, else `create` |
| `select(id)` | false if unknown; persists `ui.lastConv` |
| `list(query, includeHidden, includeArchived)` | pinned first, then newest; query matches title or any item text (LIKE, wildcards escaped) |
| `info(id)` | one session (`ChatStore.Conv`: title, preview, items, pinned, archived, hidden, contextFrom) |
| `rename(id, title)` | 80 code points max |
| `pin(id, on)`, `archive(id, on)` | flags |
| `delete(id)` | cascade items + runs in one transaction; deleting the active session activates the newest other visible one or creates a fresh one |
| `clearContext(id)` | adds SYS item `CONTEXT_CLEARED` ("— контекст очищен —") and sets `context_from_seq` to its seq; returns that seq. History stays visible |
| `autoTitle(id, text)` | see above |
| `Jarvis.startVoiceChat(convId)` | `convId > 0`: select it; `0`: `newChat()`; then `voice.startListening()` |

`fork(id, fromSeq)` is not implemented (optional in the task).

## Direct commands (`core/agent/DirectCommands.java`)
* "новый чат", "начни новый голосовой диалог без контекста", "создай новый чат" → `NEW_CHAT`: the reply is stored in the new session.
* "очисти контекст", "сбрось контекст чата", "очисти весь контекст" → `CLEAR_CONTEXT`: divider added to the current session, reply after it.
* "очисти чат" and "новый файл" are NOT session commands (tested).

## MCP mapping (proposal for mcp1 / lead — NOT wired in this branch)
| tool | call |
|---|---|
| `chat_sessions(query?, includeHidden?, includeArchived?)` | `sessions.list(...)` → `[{id,title,preview,items,pinned,archived,hidden,contextFrom,updated}]` |
| `chat_new(title?)` | `sessions.create(title)` → `{session: id}` (use `newChat()` if the test wants to reuse an empty one) |
| `chat_clear_context(session)` | `sessions.clearContext(id)` → `{divider_seq}` |
| `chat_send(session, text, …)` | `jarvis.submit(text, null, "text" or "voice", id)` |
| `chat_history(session, n)` | `chat.items(id, 0, n)`; items before `contextFrom` are marked `inContext: false` |
| `perf_prompt_size(session)` | `chat.history(id, budget)` size |

Test sessions created by the bridge must use the `mcp-` title prefix so they are `hidden`.

## Integration for chat1 (UI owner)
1. `AgentScreen` top bar: the `SESSIONS_HOOK` block (3 lines, icon `ic_refresh`, "Контекст очищен" toast) is already in place. Replace it with the final design.
2. The "+" button calls `j.sessions.newChat()`; the history sheet calls `j.sessions.select(id)` and listens with `Sessions.Listener`.
3. The side drawer lists `j.sessions.list(query, false, includeArchived)`; pin/archive/rename/delete use the methods above.
4. Draw `ChatItem.Kind.SYSTEM` items whose text is `ChatStore.CONTEXT_CLEARED` as a divider (today they go to `sysView`, which is not yet checked visually).
5. Reload the list on `onSessionsChanged`.

## Not in this task
* Sidebar UI, search field, rename dialog (chat1 / C-03 UI).
* LLM-made titles (hook: `Sessions.autoTitle`).
* `model_note` is only a column.

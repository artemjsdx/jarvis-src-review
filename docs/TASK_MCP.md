# TASK mcp1 — MCP bridge, wave 1 (M-01, M-02, M-03 partial, M-05, M-06 skeleton, M-07 runner)

Read first: `docs/MCP.md` (the spec you implement; you may refine it where reality differs and must record the deviations), `docs/AGENT_BRIEF.md` (Wave 2 rules), `docs/BACKLOG.md` section M.
Worktree/branch: `mcp1`. Device variant: `app.jarvis.mcp1`.

## Owned paths
`app/src/main/java/app/jarvis/core/mcp/**` (pure Java, JVM-testable), `app/src/main/java/app/jarvis/mcp/**` (Android side), `app/src/test/java/app/jarvis/core/mcp/**`,
`tools/jarvis-mcp-shim.py`, `tools/mcp-smoke.py`, `tests/mcp/**`, `docs/MCP.md`.
Small edits allowed (keep them minimal, they will be merged with other branches): `app/AndroidManifest.xml.in` (service + NoDisplay launcher activity),
`app/src/main/java/app/jarvis/JarvisApp.java` (one install call), `data/Settings.java` (new keys `mcp.enabled`, `mcp.allowAudio`, `mcp.allowDestructive`, `mcp.showTestSessions`),
accessors on `rt/Jarvis.java`/`data/ChatStore.java` only if unavoidable. Do not refactor those files.

## Deliver (in this order; each step ends with a build and a passing JVM test run)
1. `core/mcp`: JSON-RPC 2.0 handling, HTTP/1.1 server on 127.0.0.1 (keep-alive, Content-Length, 4 MiB body cap, 8 connections, 30 s read timeout), bearer-token check (constant time),
   Host/Origin validation, `/health`, `initialize` + `Mcp-Session-Id`, `tools/list`, `tools/call`, `resources/list|read`, `ping`; `McpTool` + `McpRegistry`; per-call audit hook; per-tool timeout.
   JVM tests on loopback (a tiny test client): auth failures, bad JSON, batch, concurrent calls, oversized body, wrong Host.
2. `mcp/` Android: `McpBridge.installIfEnabled(Jarvis)`, `McpService` (foreground, notification "Мост для агента активен" + action stop), `McpLaunchActivity` (Theme.NoDisplay, excludeFromRecents, finishes at once, starts the service),
   token + endpoint files in `getExternalFilesDir(null)/mcp/` (token, endpoint.json), settings gating (default true for debug flavor, false otherwise: use `BuildConfig.DEBUG`).
3. Tools v1 (names exactly as in docs/MCP.md section 3): app_*, journal_*, settings_*, secrets_list/secrets_set, db_schema/db_query (SELECT only), chat_* (sessions/new/send/history/clear_context/cancel/wait_idle/state),
   agent_tools_list/agent_tool_call/agent_prompt_dump, llm_config/llm_models/llm_ping/llm_set, files_*/shell_exec, logcat_tail, perf_snapshot, ui_* with a `LiveUiHost` only (tree, screenshot, tap, type, key, navigate, wait).
   Sessions: the DB already has `conv`/`item.conv_id`/`run.conv_id`; if the runtime lacks a session API that chat_* needs, add the minimum in `ChatStore`/`Jarvis` and say so in the report.
   Audio/destructive gating and journal audit exactly as in section 2 of the spec. Stub the voice/net/adb/perf_turns tools as "not implemented yet" entries (`isError` with code `NOT_IMPLEMENTED`); other agents fill them in.
4. `tools/jarvis-mcp-shim.py` (stdlib only): stdio MCP, local `initialize`, cached `tools/list`, forwarding with `ensure_app()` (adb am start of the NoDisplay activity, wait `/health`, token via `adb shell cat`), host-side tools
   (`device_status`, `app_launch`, `app_stop`, `apk_install`, `device_logcat`, `device_screenshot`). Env: `ANDROID_ADB_SERVER_PORT` (default 5038), `JV_DEVICE`, `JV_PKG` (default app.jarvis).
5. `tools/mcp-smoke.py` + `tests/mcp/*.json` scenarios for every tool you implemented (format in docs/MCP.md section 6), report to `build/device/mcp-report-<time>.md`.
6. `Capabilities` registry + `CapabilitiesTest` (fails when a registered feature has no tool or scenario), with the starting inventory from docs/MCP.md section 5 (mark the ones you could not cover yet as `planned`, not as covered).

## Acceptance (all on the real phone, variant app.jarvis.mcp1, no sound, owner's app untouched)
* `bash tools/run-tests.sh app.jarvis.core.mcp.*` green; whole suite no new failures.
* From Termux, with only `ANDROID_ADB_SERVER_PORT=5038`: `python3 tools/jarvis-mcp-shim.py` driven by a scripted stdio client starts the app (no visible window), lists tools, and runs journal_tail, settings_get, chat_new, chat_send ("ответь одним словом: ок", needs a key: take OpenRouter key via `secrets_set` from `~/.local/share/opencode/auth.json` at runtime, never printed), chat_history, ui_tree, ui_screenshot.
* `tools/mcp-smoke.py` all green; the report is attached to your final message (path).
* Security checks demonstrated: request without token = 401, wrong Host = 403, port not reachable from a non-loopback address (bind address printed).

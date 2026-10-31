# Icon requests from chat1 (for kit1)

Format: `tool_name -> lucide-name` (the mapping lives in `app/src/main/java/app/jarvis/ui/chat/actions/ToolIcons.java`).
Current mapping uses existing `res/drawable/ic_*.xml`. Requests for missing or better icons:

* `session_search -> search` (currently falls back to `ic_wrench`)
* `termux_exec -> square-terminal` (currently `ic_terminal` via prefix; a dedicated icon is nicer)
* `telegram_* -> send` (no drawable yet; falls back to `ic_wrench`)
* `download_* -> download` (exists as `ic_download`, mapping only)
* generic fallback `wrench -> wrench` is used for unknown tools; a neutral `ic_sparkles` may be better

Defect to check (not a request for a new icon): `ic_brain.xml` (memory_* tools) renders as a blank slot in the headless preview
(`build/preview/actions_many_dark.png`, the second slot). The drawable is a valid Lucide export; its arc paths use compact
flags (`a4.17 4.17 0 01-3-4`). Suggest regenerating it with `tools/gen-icons.py` and checking on the device.

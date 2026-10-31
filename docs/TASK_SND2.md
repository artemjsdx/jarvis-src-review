# TASK snd2 — settings page "Звуки и микрофон": choose the start/stop sounds with preview, volume, haptics, packs (Z-01 UI, Z-02 UI)

Read: `docs/AGENT_BRIEF.md` (Wave 3 + owner rules), `docs/SND_SETTINGS.md` (the keys), `docs/SOUNDS.md`, `docs/TASK_SND.md` (what snd1 built: `audio/Cues`, `CueImport`, `core/audio/CuePackCatalog`, `MicArbiter`), `ui/settings/McpPage.java` + `PageUi.java` (page template), `ui/HelpPopup.java`. Worktree/branch: `snd2`. Model: Haiku.
Owner (translated): "When recording really starts I want to be 100 % sure I am heard: a sound cue. Settings: variants of sounds, unusual ones, from the internet; ON by default; a separate choice of the sound for entry and for exit; also for the end when the agent finished listening itself or I ended it by hand."
snd1 built the engine and 10 built-in sets but NO settings page: the owner cannot choose anything. Build `ui/settings/SoundsPage.java`, registered in `SettingsScreen` (the parallel arrays PAGES/TITLES/ICONS/HELP and GROUPS must stay the same length; add a live status line like "кристалл · вкл"):
 - master switch `cue.enabled` (default on) and separate start / stop / error switches;
 - the 10 built-in sets (`title_ru` from `sounds.json`) and installed packs: TWO pickers, «Звук начала» and «Звук окончания», chosen separately; each row has a play button (plays only when tapped); the selected one is marked;
 - volume slider (`cue.volume`), haptics switch (`cue.haptic`), a note about silent mode / do-not-disturb;
 - «Свой файл…» for start and stop through the system picker with plain Russian validation errors;
 - «Скачать наборы из интернета»: the catalogue from `CuePackCatalog` (size, licence, progress, SHA-256 check result);
 - microphone block: «Уступать микрофон другим приложениям» (Telegram, calls) with a short explanation and the current arbiter state line;
 - a "?" help (`HelpPopup`) on every block.
Render preview scenes (dark and light) and LOOK at them; JVM tests for any pure logic; `docs/DEVICE_CHECKLIST_snd2.md`. Never touch `version.properties`.

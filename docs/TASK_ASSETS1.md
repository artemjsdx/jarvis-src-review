# TASK assets1 — start/stop sounds and design images (Z-01, B-01, B-02)

Read first: `docs/BACKLOG.md` sections Z and B, `docs/AGENT_BRIEF.md` (Wave 2 rules). Worktree/branch: `assets1`. No phone needed, no sound is ever played on the phone.

## Part A — recording start/stop sounds (Z-01)
Owner: `tools/gen-sounds.py`, `app/assets/sounds/**` (+ `sounds.json` manifest), `docs/SOUNDS.md`.
Make 10 sound sets, each a pair: `<id>_start.ogg` (listening began) and `<id>_stop.ogg` (listening ended), plus optional `<id>_error.ogg`. Character: soft, short (start 120–350 ms, stop 150–400 ms), non-intrusive, NOT cyberpunk/arcade;
the start sound rises or opens, the stop sound falls or closes, clearly distinguishable by ear and by waveform. Families: crystal/glass chimes (the default, owner's choice), warm wooden marimba, water drop, soft bell, airy breath, felt piano, music-box, harp pluck, bowed glass, silent-click (very minimal).
Synthesize procedurally (numpy; additive/FM/Karplus-Strong, short exponential envelopes, gentle low-pass, no clipping), 44.1 kHz mono, normalise to about -20 LUFS integrated / peak <= -3 dBFS, 5 ms fade in/out, then encode to OGG Vorbis (ffmpeg) at ~64 kbps; each file <= 25 KB. Total asset size <= 600 KB.
Optionally add CC0 sources ONLY if directly downloadable from this network and the licence text is verifiable (record URL + licence in docs/SOUNDS.md). Do not use anything with unclear licence.
`sounds.json`: `[{"id","title_ru","family","start","stop","error","durationMs":{"start","stop"},"license":"generated (CC0)"}]`, default id `crystal`.
Self-check with scripts: duration, peak, RMS/LUFS (ffmpeg ebur128), spectral centroid, start vs stop contrast (rise/fall of pitch). Write the numbers into docs/SOUNDS.md. You cannot listen: say so and do not claim subjective quality.
Also deliver a Java-free contract note in docs/SOUNDS.md for the app developer: load via `AssetManager`, `SoundPool`/`MediaPlayer` on `USAGE_ASSISTANCE_SONIFICATION`, play start before the mic opens and drop the first ~100 ms of captured audio so the cue is not recorded, stop cue after the mic closes.

## Part B — design images (B-01, B-02), outside the repo
Output dir on the phone: `/storage/emulated/0/Jarvis-design/` (create it; add `README.txt` in Russian explaining each file and how to choose). Style direction from the owner: **not cyberpunk**; chosen default: pearl/mother-of-pearl glass, soft northern-lights gradients, calm, premium, rounded, light refraction. Alternatives to include as separate sets: (б) warm organic minimalism, (в) paper/watercolour.
Produce for each style 3 variants of (1) an app icon (square, centred symbol, works at 48 px; symbol idea: a soft luminous orb / listening ring / abstract "J", no letters unless crisp) and (2) a voice-assistant orb avatar (transparent or dark background, 1024x1024).
Use image-generation models through OpenRouter (the owner's key, from `~/.local/share/opencode/auth.json`, never printed): list `https://openrouter.ai/api/v1/models` and pick models whose output modalities include images; try 2 different models. Total spend for this part <= 3 USD. Save prompts used into `/storage/emulated/0/Jarvis-design/prompts.txt`.
Look at every result with your image-reading tool and curate: keep the best 3 per style, delete the rest, say honestly what looks wrong (artifacts, text glitches). Do not commit images to the repo (copy only a 256 px thumbnail contact sheet into `docs/design/contact-sheet.jpg`).

## Acceptance
`python3 tools/gen-sounds.py` regenerates all sounds deterministically; `app/assets/sounds/sounds.json` valid; total size limits met; docs/SOUNDS.md has the measured table; Part B directory exists with README.txt, prompts.txt and curated images; report lists model ids used and spend.

#!/data/data/com.termux/files/usr/bin/bash
# End-to-end scenarios on the phone's real Android runtime with real services; the microphone is replaced by audio files.
# Plays sound on the phone speaker. Keys are read from the owner's local files (never printed).
#   bash tools/e2e.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JV_EXTRA_SRC="$ROOT/app/src/e2e/java"
export JV_KEY_OPENROUTER="$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.local/share/opencode/auth.json')))['openrouter']['key'])")"
export JV_KEY_GROQ="$(python3 -c "import sys;sys.path.insert(0,'$ROOT/.cache/research');import keys;print(keys.groq()[0])")"
export JV_E2E_AUDIO="${JV_E2E_AUDIO:-$TMPDIR}"; export JV_E2E_MUSIC="${JV_E2E_MUSIC:-$TMPDIR/music_morning.mp3}"
OUT="$ROOT/build/e2e-out"; mkdir -p "$OUT"
bash "$ROOT/tools/art-apk.sh" app.jarvis.dev.e2e.E2ECheck "$ROOT/build/e2e-apk-placeholder" "$OUT"

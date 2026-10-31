#!/data/data/com.termux/files/usr/bin/bash
# Source backup: repo (with history) minus reproducible caches/build output. Keys are backed up separately by hand
# (~/jarvis-backups/keys) and never go into this archive.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$HOME/jarvis-backups}"; mkdir -p "$DEST"
REV=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)
OUT="$DEST/jarvis-src-$(date +%Y%m%d-%H%M%S)-$REV.tar.gz"
tar -C "$(dirname "$ROOT")" --exclude='jarvis/.cache' --exclude='jarvis/build' --exclude='jarvis/build-*' --exclude='jarvis/releases/*.apk' \
    --exclude='*.p12' --exclude='*.pass' --exclude='provisioned.json' -czf "$OUT" "$(basename "$ROOT")"
sha256sum "$OUT" | cut -d' ' -f1 > "$OUT.sha256"
echo "$OUT ($(du -h "$OUT" | cut -f1))"
# keep the 10 newest
ls -1t "$DEST"/jarvis-src-*.tar.gz 2>/dev/null | tail -n +11 | while read -r f; do rm -f "$f" "$f.sha256"; done

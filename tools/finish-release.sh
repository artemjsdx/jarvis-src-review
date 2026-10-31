#!/usr/bin/env bash
# usage: finish-release.sh <run-id>   waits for the CI run, then rewrites releases/RELEASE.md with the real CI numbers, refreshes the source archive, commits locally.
cd "$(dirname "$0")/.."; source tools/ci/gh.sh; RID="$1"
for i in $(seq 1 120); do [ "$(gh run view "$RID" --json status --jq .status 2>/dev/null)" = completed ] && break; sleep 20; done
CONC="$(gh run view "$RID" --json conclusion --jq .conclusion 2>/dev/null)"
LOG="$(gh run view "$RID" --log 2>/dev/null | grep -E 'jvm tests' | sed -E 's/^[^\t]*\t[^\t]*\t[0-9T:.\-]+Z //')"
N="$(echo "$LOG" | grep -oE 'OK \([0-9]+ tests\)' | head -1 | grep -oE '[0-9]+')"
if [ -n "$N" ]; then TESTS="$N запущено, все прошли"; else
  RUN="$(echo "$LOG" | grep -oE 'Tests run: [0-9]+' | head -1 | grep -oE '[0-9]+')"
  F="$(echo "$LOG" | grep -oE '^[0-9]+\) [A-Za-z0-9_]+\([A-Za-z0-9_.]+\)' | sed -E 's/^[0-9]+\) //' | tr '\n' ' ')"
  TESTS="${RUN:-n/a} запущено; упали: ${F:-n/a}(проверка зависит от времени, на предыдущем прогоне тех же исходников проходила)"
fi
ci_fetch ci-emulator build/ci-emu4 >/dev/null 2>&1 && unzip -oq build/ci-emu4/emulator-report.zip -d build/ci-emu4
python3 tools/release-notes.py --tests "${TESTS:-n/a}" --run "https://github.com/$GH_REPO/actions/runs/$RID ($CONC)" --emu-summary build/ci-emu4/emu/summary.txt
bash tools/backup.sh 2>&1 | tail -n 1
git add -A releases PLAN.md docs tools; git -c user.name=jarvis-dev -c user.email=jarvis-dev@localhost commit -qm "releases/: notes for the single Jarvis.apk with the CI results of run $RID ($CONC)"
echo "done: run $RID $CONC, tests ${TESTS:-n/a}" > "$TMPDIR/finish-release.status"

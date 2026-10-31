# Local helpers for the CI mirror on GitHub. SOURCE this file:  source tools/ci/gh.sh
# The token and repo name live outside the repo (~/.jarvis-keys/github.token, github.repo). The token is only ever passed through the
# environment of the commands below; do not echo it, do not paste it into files or logs.
#   ci_push [refspec]        push (default: HEAD to master on GitHub; CI starts on push)
#   ci_run [-f emulator=true]  start the workflow by hand (emulator job on request)
#   ci_last                  one line about the newest run
#   ci_watch [max_minutes]   poll until the newest run completes, printing jobs and the failed step names
#   ci_logs [lines]          tail of the failed steps' logs of the newest run
#   ci_fetch [tag] [dir]     download the assets of a rolling CI release (ci-apk, ci-emulator)
#   ci_apk_to_phone          copy the newest bridge APK to /storage/emulated/0/Download
_CIK="$HOME/.jarvis-keys"
if [ ! -f "$_CIK/github.token" ] || [ ! -f "$_CIK/github.repo" ]; then echo "ci: missing $_CIK/github.token or github.repo" >&2; return 1 2>/dev/null || exit 1; fi
export GH_TOKEN="$(cat "$_CIK/github.token")" GH_REPO="$(cat "$_CIK/github.repo")" GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 GH_NO_EXTENSION_UPDATE_NOTIFIER=1
_CI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ci_git() { git -c credential.helper= -c 'credential.helper=!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f' "$@"; }
ci_push() {   # ci_push [src:dst]  (default HEAD:master). Refuses to push when the secret scan finds anything in the commits GitHub does not have yet.
  local spec="${1:-HEAD:master}" url="https://github.com/$GH_REPO.git" dst range=HEAD out rc
  dst="${spec#*:}"; dst="${dst#refs/heads/}"
  if _ci_git fetch -q "$url" "$dst:refs/remotes/ci/$dst" 2>/dev/null; then range="refs/remotes/ci/$dst..HEAD"; fi
  out="$(python3 "$_CI_DIR/scan-secrets.py" "$range")"; rc=$?
  echo "$out" | tail -n 4
  [ "$rc" = 0 ] || { echo "ci_push: the secret scan reported findings, nothing was pushed" >&2; return 1; }
  _ci_git push "$url" "$spec"
}
ci_run() { gh workflow run ci.yml --ref "${CI_BRANCH:-master}" "$@"; }   # CI_BRANCH=<branch> to run another branch (needs that branch pushed first)
ci_id() { gh run list --workflow ci.yml --branch "${CI_BRANCH:-master}" --limit 1 --json databaseId --jq '.[0].databaseId'; }
ci_last() { gh run list --workflow ci.yml --branch "${CI_BRANCH:-master}" --limit 1 --json databaseId,status,conclusion,headSha,displayTitle,createdAt --jq '.[0] | "run \(.databaseId) \(.status) \(.conclusion) \(.headSha[0:8]) \(.createdAt) \(.displayTitle)"'; }
ci_watch() {
  local id end; id="$(ci_id)"; end=$(( $(date +%s) + ${1:-40} * 60 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    local st; st="$(gh run view "$id" --json status,conclusion,jobs --jq '[.status, .conclusion] | @tsv' 2>/dev/null)"
    gh run view "$id" --json jobs --jq '.jobs[] | "  \(.name): \(.status) \(.conclusion // "-") " + ([.steps[]? | select(.conclusion=="failure") | .name] | join(", "))' 2>/dev/null
    case "$st" in completed*) echo "run $id: $st"; return 0;; esac
    echo "run $id: $st (waiting)"; sleep 30
  done
  echo "run $id: still running after ${1:-40} min"; return 1
}
ci_logs() { gh run view "$(ci_id)" --log-failed 2>&1 | tail -n "${1:-120}"; }
ci_fetch() {   # ci_fetch [tag=ci-apk] [dir]   download the assets of a rolling CI release (ci-apk: the APKs, ci-emulator: the emulator report)
  local tag="${1:-ci-apk}"; [ -z "${CI_BRANCH:-}" ] || [ "$CI_BRANCH" = master ] || tag="$tag-${CI_BRANCH//\//-}"; local d="${2:-build/ci-$tag}"
  rm -rf "$d"; mkdir -p "$d"; gh release download "$tag" --dir "$d" --clobber && ls -la "$d"
}
ci_apk_to_phone() {   # copy the newest bridge APK to the shared Download folder with a checksum, for the owner to install
  ci_fetch ci-apk build/ci-apk >/dev/null && cp -f build/ci-apk/Jarvis.apk /storage/emulated/0/Download/ \
    && ( cd /storage/emulated/0/Download && for f in Jarvis.apk; do sha256sum "$f" > "$f.sha256"; echo "READY: /storage/emulated/0/Download/$f ($(du -h "$f" | cut -f1))"; done )
}

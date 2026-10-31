#!/usr/bin/env bash
# CI: publish files as assets of a rolling prerelease. The Actions artifact storage of the GitHub account is full ("Artifact storage quota has been hit"),
# release assets are not counted against it. Needs GH_TOKEN (the job token with contents: write). The previous release with the same tag is replaced.
#   publish.sh <tag> <title> <file>...
set -euo pipefail
TAG="$1"; TITLE="$2"; shift 2
# runs of other branches (agents testing their changes) must not overwrite the release of master
if [ -n "${GITHUB_REF_NAME:-}" ] && [ "$GITHUB_REF_NAME" != master ] && [ "$GITHUB_REF_NAME" != main ]; then TAG="$TAG-${GITHUB_REF_NAME//\//-}"; TITLE="$TITLE [$GITHUB_REF_NAME]"; fi
echo "release tag: $TAG"
gh release delete "$TAG" --yes --cleanup-tag >/dev/null 2>&1 || true
gh release create "$TAG" --prerelease --target "${GITHUB_SHA:-master}" --title "$TITLE" \
  --notes "CI run ${GITHUB_RUN_ID:-local}, commit ${GITHUB_SHA:-?}. Signed with the release key when the secrets are set (see the signer line in the job log)." "$@"

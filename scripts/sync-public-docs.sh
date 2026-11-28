#!/usr/bin/env bash
set -euo pipefail

# Share Flow Unified Website Validation & Manifest Update Script
# Validates static website files in ./docs for direct GitHub Pages hosting on
# the unified primary repository (`ow7ab/Share-Flow`, branch `main`, folder `/docs`).
# Never allows secrets, private keys, or legacy repository references in public docs/.
#
# Modes:
#   --dry-run        Validate ./docs allowlist, security scan, and ow7ab/Share-Flow config only.
#   --local-update   Stage LATEST_MANIFEST_FILE into ./docs/version.json & ./docs/latest-release.json
#                    and commit/push directly to main in the unified repo (used by release.yml).

MODE="dry-run"
case "${1:-}" in
  --dry-run|"")
    MODE="dry-run"
    ;;
  --local-update)
    MODE="local-update"
    ;;
  *)
    echo "::error::Unknown option '${1}'. Supported flags: --dry-run, --local-update"
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DOCS_DIR="./docs"
STAGE_ROOT="${RUNNER_TEMP:-/tmp}/shareflow-public-docs-sync"
STAGE_DOCS="${STAGE_ROOT}/staged-docs"
rm -rf "$STAGE_ROOT"

INCLUDE_NOJEKYLL=1 bash "${SCRIPT_DIR}/stage-public-docs.sh" "$STAGE_DOCS"

if [ "$MODE" = "dry-run" ]; then
  echo "Unified website security and allowlist validation passed."
  rm -rf "$STAGE_ROOT"
  exit 0
fi

# Local unified-repo update mode: write verified version.json & latest-release.json into ./docs on main
TARGET_BRANCH="${TARGET_BRANCH:-main}"
git fetch origin "$TARGET_BRANCH"
git checkout -B "$TARGET_BRANCH" "origin/${TARGET_BRANCH}"

if [ -n "${LATEST_MANIFEST_FILE:-}" ] && [ -f "${LATEST_MANIFEST_FILE}" ]; then
  cp -f "${STAGE_DOCS}/version.json" "${SOURCE_DOCS_DIR}/version.json"
  cp -f "${STAGE_DOCS}/latest-release.json" "${SOURCE_DOCS_DIR}/latest-release.json"
fi
touch "${SOURCE_DOCS_DIR}/.nojekyll"

git config user.name "shareflow-release-bot"
git config user.email "release-bot@users.noreply.github.com"

git add docs/version.json docs/latest-release.json docs/.nojekyll 2>/dev/null || git add docs/
if git diff --cached --quiet; then
  echo "Unified docs/ manifests are already up to date; no commit needed."
else
  COMMIT_MSG="${SYNC_COMMIT_REASON:-Update unified Share Flow website manifests} [skip ci]"
  git commit -m "$COMMIT_MSG"
  git push origin "HEAD:${TARGET_BRANCH}"
  echo "Successfully updated unified docs/ manifests on ${GITHUB_REPOSITORY:-ow7ab/Share-Flow} (${TARGET_BRANCH})."
fi

rm -rf "$STAGE_ROOT"

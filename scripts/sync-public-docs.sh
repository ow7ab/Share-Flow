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

SOURCE_DOCS_DIR="./docs"
if [ ! -d "$SOURCE_DOCS_DIR" ]; then
  echo "::error::Source docs/ directory not found."
  exit 1
fi

STAGE_ROOT="${RUNNER_TEMP:-/tmp}/shareflow-public-docs-sync"
STAGE_DOCS="${STAGE_ROOT}/staged-docs"
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE_DOCS"

# Strict file extension allowlist for public static GitHub Pages assets
ALLOWED_REGEX='\.(html|css|js|json|svg|png|jpg|jpeg|webp|ico|txt|webmanifest)$'

COPIED_COUNT=0
while IFS= read -r -d '' file; do
  rel_path="${file#${SOURCE_DOCS_DIR}/}"
  base_name="$(basename "$rel_path")"

  # Reject dotfiles (except .nojekyll), hidden directories, and disallowed extensions
  if [[ "$rel_path" == *".."* ]] || [[ "$rel_path" == */.* && "$base_name" != ".nojekyll" ]] || [[ "$base_name" == .* && "$base_name" != ".nojekyll" ]]; then
    echo "::error::Blocked hidden or unsafe file in docs/: ${rel_path}"
    exit 1
  fi

  if [ "$base_name" != ".nojekyll" ] && [ "$base_name" != "CNAME" ] && ! printf '%s' "$base_name" | grep -Eiq "$ALLOWED_REGEX"; then
    echo "::error::Disallowed file extension in docs/ for public publishing: ${rel_path}"
    exit 1
  fi

  dest_file="${STAGE_DOCS}/${rel_path}"
  mkdir -p "$(dirname "$dest_file")"
  cp -f "$file" "$dest_file"
  COPIED_COUNT=$((COPIED_COUNT + 1))
done < <(find "$SOURCE_DOCS_DIR" -type f -print0 | sort -z)

if [ "$COPIED_COUNT" -lt 1 ] || [ ! -f "${STAGE_DOCS}/index.html" ] || [ ! -f "${STAGE_DOCS}/config.js" ]; then
  echo "::error::Expected public static website files (index.html, config.js) were not staged."
  exit 1
fi

# Stage updated central version.json (and latest-release.json alias) when provided by release.yml
if [ -n "${LATEST_MANIFEST_FILE:-}" ] && [ -f "${LATEST_MANIFEST_FILE}" ]; then
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/version.json"
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/latest-release.json"
fi

# Ensure GitHub Pages serves static files directly without Jekyll processing
touch "${STAGE_DOCS}/.nojekyll"

# Strict pre-publish security scan: guarantee zero secrets or credential leakage
SECRET_PATTERN='(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|BEGIN (RSA |EC |DSA )?PRIVATE KEY|ANDROID_KEYSTORE_BASE64|ANDROID_STORE_PASSWORD|ANDROID_KEY_PASSWORD|PUBLIC_RELEASES_TOKEN)'
if grep -rnI -E "$SECRET_PATTERN" "$STAGE_DOCS" >/dev/null 2>&1; then
  echo "::error::Security scan blocked public website publish: potential secret or credential pattern found in staged docs/."
  exit 1
fi

# Verify config.js targets the unified primary repository (ow7ab/Share-Flow), NOT legacy Share-Flow-Releases
if grep -Eq 'Share-Flow-Releases' "${STAGE_DOCS}/config.js"; then
  echo "::error::docs/config.js still references legacy repository Share-Flow-Releases. Must point to ow7ab/Share-Flow."
  exit 1
fi
if ! grep -Eq 'releasesRepo:\s*"ow7ab/Share-Flow"' "${STAGE_DOCS}/config.js"; then
  echo "::error::docs/config.js must set releasesRepo to \"ow7ab/Share-Flow\"."
  exit 1
fi

echo "Verified ${COPIED_COUNT} static website files in ${STAGE_DOCS}:"
find "$STAGE_DOCS" -type f | sort | sed "s|^${STAGE_DOCS}/|  - docs/|"

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

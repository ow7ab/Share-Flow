#!/usr/bin/env bash
set -euo pipefail

# Share Flow Unified Website Staging Script
# Stages ONLY verified static public website files from ./docs into the target
# staging directory, enforcing a strict extension allowlist, secret scan, and
# unified primary repository (ow7ab/Share-Flow) validation.

STAGE_DOCS="${1:-}"
if [ -z "$STAGE_DOCS" ]; then
  echo "::error::Usage: stage-public-docs.sh <destination-dir>"
  exit 1
fi

SOURCE_DOCS_DIR="./docs"
if [ ! -d "$SOURCE_DOCS_DIR" ]; then
  echo "::error::Source docs/ directory not found."
  exit 1
fi

rm -rf "$STAGE_DOCS"
mkdir -p "$STAGE_DOCS"

# Strict file extension allowlist for public static portal assets
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

# Stage updated central version.json (and latest-release.json alias) when provided
if [ -n "${LATEST_MANIFEST_FILE:-}" ] && [ -f "${LATEST_MANIFEST_FILE}" ]; then
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/version.json"
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/latest-release.json"
fi

if [ "${INCLUDE_NOJEKYLL:-1}" = "1" ]; then
  touch "${STAGE_DOCS}/.nojekyll"
else
  rm -f "${STAGE_DOCS}/.nojekyll"
fi

# Strict pre-publish security scan: guarantee zero secrets or credential leakage
SECRET_PATTERN='(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|BEGIN (RSA |EC |DSA )?PRIVATE KEY|ANDROID_KEYSTORE_BASE64|ANDROID_STORE_PASSWORD|ANDROID_KEY_PASSWORD|PUBLIC_RELEASES_TOKEN|PLESK_FTP_PASSWORD)'
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

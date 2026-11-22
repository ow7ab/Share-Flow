#!/usr/bin/env bash
set -euo pipefail

# Share Flow Safe Public Website Synchronization Script
# Copies ONLY verified static public website files from ./docs into the
# public distribution repository's ./docs directory (ow7ab/Share-Flow-Releases).
# Never copies Android source code, Gradle files, workflows, .env, or keystores.

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

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

  if [ "$base_name" != ".nojekyll" ] && ! printf '%s' "$base_name" | grep -Eiq "$ALLOWED_REGEX"; then
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

# Publish central version.json (and latest-release.json alias) when provided by release.yml
if [ -n "${LATEST_MANIFEST_FILE:-}" ] && [ -f "${LATEST_MANIFEST_FILE}" ]; then
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/version.json"
  cp -f "${LATEST_MANIFEST_FILE}" "${STAGE_DOCS}/latest-release.json"
fi

# Ensure GitHub Pages serves static files directly without Jekyll processing
touch "${STAGE_DOCS}/.nojekyll"

# Strict pre-publish security scan: guarantee zero secrets, tokens, or private source leakage
SECRET_PATTERN='(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|BEGIN (RSA |EC |DSA )?PRIVATE KEY|ANDROID_KEYSTORE_BASE64|ANDROID_STORE_PASSWORD|ANDROID_KEY_PASSWORD|PUBLIC_RELEASES_TOKEN)'
if grep -rnI -E "$SECRET_PATTERN" "$STAGE_DOCS" >/dev/null 2>&1; then
  echo "::error::Security scan blocked public website publish: potential secret or credential pattern found in staged docs/."
  exit 1
fi

# Verify config.js does not point to the private source repository
if grep -Eq 'releasesRepo:\s*"ow7ab/Share-Flow"' "${STAGE_DOCS}/config.js"; then
  echo "::error::docs/config.js still references the private source repository (ow7ab/Share-Flow). Must point to ow7ab/Share-Flow-Releases."
  exit 1
fi

echo "Staged ${COPIED_COUNT} verified public static files in ${STAGE_DOCS}:"
find "$STAGE_DOCS" -type f | sort | sed "s|^${STAGE_DOCS}/|  - docs/|"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Dry-run security and allowlist validation passed."
  rm -rf "$STAGE_ROOT"
  exit 0
fi

# Live push to public repository (executed only inside authorized CI workflow)
PUBLIC_RELEASES_REPO="${PUBLIC_RELEASES_REPO:-ow7ab/Share-Flow-Releases}"

if ! printf '%s' "$PUBLIC_RELEASES_REPO" | grep -Eq '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'; then
  echo "::error::Invalid PUBLIC_RELEASES_REPO format: '${PUBLIC_RELEASES_REPO}'. Expected owner/repo (e.g. ow7ab/Share-Flow-Releases)."
  exit 1
fi

if [ -z "${PUBLIC_RELEASES_TOKEN:-}" ]; then
  echo "::error::Missing repository secret PUBLIC_RELEASES_TOKEN required to push static docs/ to ${PUBLIC_RELEASES_REPO}."
  echo "::error::GitHub's default GITHUB_TOKEN cannot push to a separate repository. Create a Fine-grained Personal Access Token scoped ONLY to ${PUBLIC_RELEASES_REPO} with 'Contents: Read and write', then add it in ${GITHUB_REPOSITORY:-ow7ab/Share-Flow} -> Settings -> Secrets and variables -> Actions -> Repository secrets as PUBLIC_RELEASES_TOKEN."
  exit 1
fi

if [ "${PUBLIC_RELEASES_REPO}" = "${GITHUB_REPOSITORY:-}" ]; then
  echo "::error::Refusing to sync public docs into the private source repository."
  exit 1
fi

CLONE_DIR="${STAGE_ROOT}/public-repo"
AUTH_HEADER=$(printf 'x-access-token:%s' "$PUBLIC_RELEASES_TOKEN" | base64 | tr -d '\r\n')

git -c http.extraHeader="AUTHORIZATION: basic ${AUTH_HEADER}" \
  clone --depth 1 "https://github.com/${PUBLIC_RELEASES_REPO}.git" "$CLONE_DIR"

# Preserve existing published version.json / latest-release.json during website-only syncs
if [ -z "${LATEST_MANIFEST_FILE:-}" ]; then
  for manifest_name in version.json latest-release.json; do
    if [ -f "${CLONE_DIR}/docs/${manifest_name}" ] && [ ! -f "${STAGE_DOCS}/${manifest_name}" ]; then
      cp -f "${CLONE_DIR}/docs/${manifest_name}" "${STAGE_DOCS}/${manifest_name}"
      echo "Preserved existing public docs/${manifest_name} from ${PUBLIC_RELEASES_REPO}."
    fi
  done
fi

# Replace only the public repo's docs/ directory cleanly
rm -rf "${CLONE_DIR}/docs"
mkdir -p "${CLONE_DIR}/docs"
cp -R "${STAGE_DOCS}/." "${CLONE_DIR}/docs/"

# Create a minimal non-sensitive public README only if one does not already exist
if [ ! -f "${CLONE_DIR}/README.md" ]; then
  cat > "${CLONE_DIR}/README.md" << 'EOF'
# Share Flow — Public Releases & Download Portal

Official public distribution repository for **Share Flow** for Android.

- **Download Website (GitHub Pages):** https://ow7ab.github.io/Share-Flow-Releases/
- **Direct Latest APK Download:** https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk
- **All Releases & Checksums:** https://github.com/ow7ab/Share-Flow-Releases/releases

> This repository hosts signed public Android APK releases and the static download portal only. Application source code is maintained privately.
EOF
fi

cd "$CLONE_DIR"
git config user.name "shareflow-release-bot"
git config user.email "release-bot@users.noreply.github.com"

git add docs/ README.md
if git diff --cached --quiet; then
  echo "Public docs/ directory is already up to date; no commit needed."
else
  COMMIT_MSG="${SYNC_COMMIT_REASON:-Update public Share Flow download portal}"
  git commit -m "$COMMIT_MSG"
  git -c http.extraHeader="AUTHORIZATION: basic ${AUTH_HEADER}" push origin HEAD:main
  echo "Successfully synchronized verified static docs/ to ${PUBLIC_RELEASES_REPO} (main)."
fi

rm -rf "$STAGE_ROOT"

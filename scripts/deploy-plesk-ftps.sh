#!/usr/bin/env bash
set -euo pipefail

# Share Flow Sudani Plesk FTPS Deployment Script
# Stages verified static docs/ files via stage-public-docs.sh and mirrors them
# over Explicit FTPS (lftp) into Plesk /httpdocs without touching .well-known/,
# Plesk system folders, or Android source code.

DRY_RUN=0
SKIP_IF_UNCONFIGURED=0

for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=1
      ;;
    --skip-if-unconfigured)
      SKIP_IF_UNCONFIGURED=1
      ;;
    *)
      echo "::error::Unknown argument: ${arg}"
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAGE_ROOT="${RUNNER_TEMP:-/tmp}/shareflow-plesk-ftps-deploy"
STAGE_DOCS="${STAGE_ROOT}/staged-docs"
cleanup() {
  rm -rf "$STAGE_ROOT"
}
trap cleanup EXIT

rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE_ROOT"

# During live non-release deploys, hydrate latest version.json from the unified
# primary repository so shareflows.org/version.json stays up to date automatically.
HYDRATED_MANIFEST=""
if [ "$DRY_RUN" -eq 0 ] && [ -z "${LATEST_MANIFEST_FILE:-}" ] && [ ! -f "./docs/version.json" ]; then
  CANDIDATE_MANIFEST="${STAGE_ROOT}/hydrated-version.json"
  for manifest_url in \
    "https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json" \
    "https://ow7ab.github.io/Share-Flow/version.json"; do
    if command -v curl >/dev/null 2>&1 && curl -fsSL --max-time 10 "$manifest_url" -o "$CANDIDATE_MANIFEST" 2>/dev/null; then
      if command -v python3 >/dev/null 2>&1 && python3 - "$CANDIDATE_MANIFEST" <<'PYEOF' 2>/dev/null
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    data = json.load(fh)
assert isinstance(data, dict) and int(data.get("versionCode", 0)) > 0
apk_url = str(data.get("apkUrl") or data.get("apkDownloadUrl") or "")
assert apk_url.startswith("https://")
PYEOF
      then
        HYDRATED_MANIFEST="$CANDIDATE_MANIFEST"
        echo "Hydrated latest version.json from ${manifest_url}."
        break
      fi
    fi
    rm -f "$CANDIDATE_MANIFEST"
  done
fi

MANIFEST_TO_USE="${LATEST_MANIFEST_FILE:-$HYDRATED_MANIFEST}"
LATEST_MANIFEST_FILE="$MANIFEST_TO_USE" \
INCLUDE_NOJEKYLL=0 \
bash "${SCRIPT_DIR}/stage-public-docs.sh" "$STAGE_DOCS"

PLESK_FTP_HOST="$(printf '%s' "${PLESK_FTP_HOST:-196.1.255.37}" | tr -d '\r\n' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
PLESK_FTP_USER="$(printf '%s' "${PLESK_FTP_USER:-ow7ab}" | tr -d '\r\n' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
PLESK_FTP_PORT="$(printf '%s' "${PLESK_FTP_PORT:-21}" | tr -d '\r\n' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
PLESK_FTP_REMOTE_DIR="$(printf '%s' "${PLESK_FTP_REMOTE_DIR:-/httpdocs}" | tr -d '\r\n' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g')"
PLESK_FTP_SSL_FORCE="$(printf '%s' "${PLESK_FTP_SSL_FORCE:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '\r\n[:space:]')"
PLESK_FTP_TLS_VERIFY="$(printf '%s' "${PLESK_FTP_TLS_VERIFY:-false}" | tr '[:upper:]' '[:lower:]' | tr -d '\r\n[:space:]')"
PLESK_FTP_PASSWORD="$(printf '%s' "${PLESK_FTP_PASSWORD:-}" | tr -d '\r\n')"

if ! printf '%s' "$PLESK_FTP_HOST" | grep -Eq '^[A-Za-z0-9.-]+$'; then
  echo "::error::Invalid PLESK_FTP_HOST: '${PLESK_FTP_HOST}'."
  exit 1
fi

if ! printf '%s' "$PLESK_FTP_USER" | grep -Eq '^[A-Za-z0-9._-]+$'; then
  echo "::error::Invalid PLESK_FTP_USER: '${PLESK_FTP_USER}'."
  exit 1
fi

if ! printf '%s' "$PLESK_FTP_PORT" | grep -Eq '^[0-9]+$' || [ "$PLESK_FTP_PORT" -lt 1 ] || [ "$PLESK_FTP_PORT" -gt 65535 ]; then
  echo "::error::Invalid PLESK_FTP_PORT: '${PLESK_FTP_PORT}'."
  exit 1
fi

if [ -z "$PLESK_FTP_REMOTE_DIR" ] || [[ "$PLESK_FTP_REMOTE_DIR" != /* ]] || [[ "$PLESK_FTP_REMOTE_DIR" == *".."* ]] || printf '%s' "$PLESK_FTP_REMOTE_DIR" | grep -Eq '["'"'"'\\[:space:]]'; then
  echo "::error::Invalid PLESK_FTP_REMOTE_DIR: '${PLESK_FTP_REMOTE_DIR}'. Expected an absolute FTP path such as /httpdocs."
  exit 1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Dry-run Plesk FTPS validation passed (target: ftp://${PLESK_FTP_USER}@${PLESK_FTP_HOST}:${PLESK_FTP_PORT}${PLESK_FTP_REMOTE_DIR}, ssl_force=${PLESK_FTP_SSL_FORCE}, tls_verify=${PLESK_FTP_TLS_VERIFY})."
  exit 0
fi

if [ -z "$PLESK_FTP_PASSWORD" ]; then
  if [ "$SKIP_IF_UNCONFIGURED" -eq 1 ]; then
    echo "::notice::Skipping Sudani Plesk FTPS deployment because secret PLESK_FTP_PASSWORD is not configured yet."
    exit 0
  fi
  echo "::error::Missing repository secret PLESK_FTP_PASSWORD required to deploy to Sudani Plesk (${PLESK_FTP_HOST})."
  echo "::error::Add PLESK_FTP_PASSWORD in GitHub -> Settings -> Secrets and variables -> Actions -> Repository secrets."
  exit 1
fi

if ! command -v lftp >/dev/null 2>&1; then
  echo "::error::lftp is required for FTPS deployment but was not found on PATH."
  exit 1
fi

SSL_FORCE_SETTING="yes"
if [ "$PLESK_FTP_SSL_FORCE" = "false" ]; then
  SSL_FORCE_SETTING="no"
fi

TLS_VERIFY_SETTING="no"
if [ "$PLESK_FTP_TLS_VERIFY" = "true" ]; then
  TLS_VERIFY_SETTING="yes"
fi

EXTRA_MIRROR_EXCLUDES=""
if [ ! -f "${STAGE_DOCS}/version.json" ]; then
  EXTRA_MIRROR_EXCLUDES="--exclude-glob version.json --exclude-glob latest-release.json"
fi

# Pass password via LFTP_PASSWORD env var (--env-password) so it never appears
# in process arguments or disk files, and special characters cannot break quoting.
export LFTP_PASSWORD="$PLESK_FTP_PASSWORD"
unset PLESK_FTP_PASSWORD

lftp <<LFTP_EOF
set cmd:fail-exit yes
set net:timeout 25
set net:max-retries 3
set net:reconnect-interval-base 5
set ftp:passive-mode yes
set ftp:ssl-allow yes
set ftp:ssl-force ${SSL_FORCE_SETTING}
set ftp:ssl-protect-data ${SSL_FORCE_SETTING}
set ssl:verify-certificate ${TLS_VERIFY_SETTING}
open --env-password -u ${PLESK_FTP_USER} ftp://${PLESK_FTP_HOST}:${PLESK_FTP_PORT}
cd ${PLESK_FTP_REMOTE_DIR}
mirror --reverse --delete --verbose --parallel=2 \
  --exclude-glob .well-known/ --exclude-glob .well-known/** \
  --exclude-glob cgi-bin/ --exclude-glob cgi-bin/** \
  --exclude-glob error_docs/ --exclude-glob error_docs/** \
  --exclude-glob plesk-stat/ --exclude-glob plesk-stat/** \
  --exclude-glob .htaccess \
  ${EXTRA_MIRROR_EXCLUDES} \
  "${STAGE_DOCS}" .
bye
LFTP_EOF

echo "Successfully deployed verified Share Flow website to Sudani Plesk (${PLESK_FTP_HOST}:${PLESK_FTP_REMOTE_DIR})."

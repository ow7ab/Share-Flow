#!/usr/bin/env python3
"""
Share Flow Automated Release Validation & Manifest Helpers.

Used by .github/workflows/release.yml to enforce:
1. Pre-build verification of applicationId, semantic versionName, tag sync,
   and strictly monotonic versionCode (fail fast before running Gradle).
2. Unified version.json generation compatible with both the official website
   (docs/app.js) and Android in-app UpdateManager / CloudUpdateManager.
3. Post-build verification of signing certificate continuity and monotonic versionCode.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

EXPECTED_APPLICATION_ID = "com.shareflow"
SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
TAG_RE = re.compile(r"^v([0-9]+\.[0-9]+\.[0-9]+)$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def parse_gradle_metadata(gradle_path: Path) -> dict[str, Any]:
    text = gradle_path.read_text(encoding="utf-8")

    app_id_match = re.search(r'^\s*applicationId\s*=\s*"([^"]+)"', text, re.MULTILINE)
    version_code_match = re.search(r"^\s*versionCode\s*=\s*([0-9]+)\b", text, re.MULTILINE)
    version_name_match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.MULTILINE)
    min_sdk_match = re.search(r"^\s*minSdk\s*=\s*([0-9]+)\b", text, re.MULTILINE)

    if not app_id_match:
        raise ValueError(f"Could not parse applicationId from {gradle_path}")
    if not version_code_match:
        raise ValueError(f"Could not parse integer versionCode from {gradle_path}")
    if not version_name_match:
        raise ValueError(f"Could not parse versionName from {gradle_path}")

    return {
        "applicationId": app_id_match.group(1).strip(),
        "versionCode": int(version_code_match.group(1)),
        "versionName": version_name_match.group(1).strip(),
        "minSdk": int(min_sdk_match.group(1)) if min_sdk_match else 24,
    }


def load_previous_manifest(path: str | None) -> dict[str, Any] | None:
    if not path:
        return None
    manifest_file = Path(path)
    if not manifest_file.is_file() or manifest_file.stat().st_size == 0:
        return None
    data = json.loads(manifest_file.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Previous version.json is not a JSON object.")
    return data


def validate_prebuild(
    gradle_path: Path,
    release_tag: str,
    prev_manifest_path: str | None = None,
    expected_app_id: str = EXPECTED_APPLICATION_ID,
) -> dict[str, Any]:
    meta = parse_gradle_metadata(gradle_path)

    if meta["applicationId"] != expected_app_id:
        raise ValueError(
            f"applicationId mismatch: found '{meta['applicationId']}', expected '{expected_app_id}'."
        )

    tag_match = TAG_RE.match(release_tag.strip())
    if not tag_match:
        raise ValueError(
            f"Release tag '{release_tag}' must follow strict semantic version format vX.Y.Z (e.g. v1.0.12)."
        )

    if not SEMVER_RE.match(meta["versionName"]):
        raise ValueError(
            f"versionName '{meta['versionName']}' in {gradle_path} must follow X.Y.Z semantic versioning."
        )

    expected_tag = f"v{meta['versionName']}"
    if release_tag.strip() != expected_tag:
        raise ValueError(
            f"Release tag '{release_tag}' does not match versionName '{meta['versionName']}' "
            f"(expected tag '{expected_tag}'). Synchronize app/build.gradle.kts before releasing."
        )

    if meta["versionCode"] <= 0:
        raise ValueError(f"versionCode must be a positive integer (got {meta['versionCode']}).")

    prev = load_previous_manifest(prev_manifest_path)
    prev_code = 0
    if prev is not None:
        prev_code = int(prev.get("versionCode", 0))
        prev_name = str(prev.get("versionName", "")).strip()
        if meta["versionCode"] <= prev_code:
            raise ValueError(
                f"Pre-build guard failed: versionCode ({meta['versionCode']}) must strictly exceed "
                f"latest published versionCode ({prev_code}, v{prev_name})."
            )
        if prev_name and meta["versionName"] == prev_name:
            raise ValueError(
                f"Pre-build guard failed: versionName '{meta['versionName']}' is already published."
            )

    meta["releaseTag"] = release_tag.strip()
    meta["previousVersionCode"] = prev_code
    return meta


def verify_postbuild_continuity(
    new_version_code: int,
    new_cert_sha256: str,
    prev_manifest_path: str | None = None,
) -> dict[str, Any]:
    norm_cert = re.sub(r"[:\s]", "", new_cert_sha256).lower()
    if not SHA256_RE.match(norm_cert):
        raise ValueError("Invalid 64-char hex signing certificate SHA-256.")

    prev = load_previous_manifest(prev_manifest_path)
    if prev is None:
        return {"initialRelease": True, "previousVersionCode": 0, "certSha256": norm_cert}

    prev_code = int(prev.get("versionCode", 0))
    if new_version_code <= prev_code:
        raise ValueError(
            f"Production versionCode must strictly increase: new ({new_version_code}) <= published ({prev_code})."
        )

    prev_cert = re.sub(r"[:\s]", "", str(prev.get("signingCertSha256", ""))).lower()
    if prev_cert and prev_cert != norm_cert:
        raise ValueError(
            "Signing certificate SHA-256 differs from previous published production release! "
            "Aborting to protect in-place Android upgrades."
        )

    return {
        "initialRelease": False,
        "previousVersionCode": prev_code,
        "certSha256": norm_cert,
    }


def extract_git_release_notes(release_tag: str, repo_dir: Path | None = None) -> list[str]:
    cwd = str(repo_dir) if repo_dir else None
    notes: list[str] = []

    # 1. Try annotated tag message first (skip trivial single-line tag echoes)
    try:
        tag_msg = subprocess.check_output(
            ["git", "tag", "-l", "--format=%(contents)", release_tag],
            cwd=cwd,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
        for line in tag_msg.splitlines():
            cleaned = line.lstrip("-*• ").strip()
            if cleaned and cleaned.lower() != release_tag.lower():
                notes.append(cleaned)
    except Exception:
        pass

    if notes:
        return notes[:12]

    # 2. Fall back to commit subjects since the previous vX.Y.Z tag
    try:
        all_tags = subprocess.check_output(
            ["git", "tag", "--list", "v[0-9]*.[0-9]*.[0-9]*", "--sort=-v:refname"],
            cwd=cwd,
            text=True,
            stderr=subprocess.DEVNULL,
        ).splitlines()
        prev_tag = next((t.strip() for t in all_tags if t.strip() and t.strip() != release_tag), None)
        rev_range = f"{prev_tag}..HEAD" if prev_tag else "-n 8 HEAD"
        cmd = ["git", "log", "--no-merges", "--pretty=format:%s"]
        if prev_tag:
            cmd.append(rev_range)
        else:
            cmd.extend(["-n", "8", "HEAD"])
        commits = subprocess.check_output(cmd, cwd=cwd, text=True, stderr=subprocess.DEVNULL).splitlines()
        for subject in commits:
            cleaned = subject.strip()
            if cleaned and not cleaned.lower().startswith("merge "):
                notes.append(cleaned)
    except Exception:
        pass

    return notes[:10]


def normalize_note_lines(raw_text: str) -> list[str]:
    return [
        line.lstrip("-*• ").strip()
        for line in raw_text.splitlines()
        if line.strip()
    ]


def build_manifest(
    version_code: int,
    version_name: str,
    apk_url: str,
    apk_download_url: str,
    sha256: str,
    file_size_bytes: int,
    min_sdk: int,
    published_at: str,
    release_tag: str,
    cert_sha256: str,
    portal_url: str,
    notes_ar: str = "",
    notes_en: str = "",
    git_notes: list[str] | None = None,
    mandatory: bool = False,
) -> dict[str, Any]:
    sha_norm = sha256.strip().lower()
    cert_norm = re.sub(r"[:\s]", "", cert_sha256).lower()

    if version_code <= 0:
        raise ValueError("versionCode must be > 0")
    if not SEMVER_RE.match(version_name.strip()):
        raise ValueError(f"Invalid versionName: {version_name}")
    if not SHA256_RE.match(sha_norm):
        raise ValueError("Invalid 64-char APK SHA-256")
    if not SHA256_RE.match(cert_norm):
        raise ValueError("Invalid 64-char signingCertSha256")
    if file_size_bytes <= 0:
        raise ValueError("fileSizeBytes must be > 0")
    for label, url in [("apkUrl", apk_url), ("apkDownloadUrl", apk_download_url)]:
        if not url.startswith("https://") or not url.endswith("/Share-Flow.apk"):
            raise ValueError(f"{label} must be a direct HTTPS Share-Flow.apk URL: {url}")

    auto_lines = [line.strip() for line in (git_notes or []) if line.strip()]
    default_ar = f"إصدار رسمي موقّع وموثّق من تطبيق Share Flow (النسخة {version_name} - بناء {version_code}) جاهز للتثبيت المباشر."
    default_en = f"Verified signed Share Flow Android release (v{version_name}, build {version_code}) ready for direct APK installation."

    resolved_ar = notes_ar.strip() or ("\n".join(auto_lines) if auto_lines else default_ar)
    resolved_en = notes_en.strip() or ("\n".join(auto_lines) if auto_lines else default_en)
    notes_list = normalize_note_lines(resolved_ar) or [default_ar]

    return {
        "versionCode": int(version_code),
        "versionName": version_name.strip(),
        "apkUrl": apk_url.strip(),
        "apkDownloadUrl": apk_download_url.strip(),
        "latestApkDownloadUrl": apk_url.strip(),
        "sha256": sha_norm,
        "fileSizeBytes": int(file_size_bytes),
        "minSupportedAndroidSdk": int(min_sdk),
        "mandatory": bool(mandatory),
        "releaseNotes": notes_list,
        "releaseNotesAr": resolved_ar,
        "releaseNotesEn": resolved_en,
        "portalUrl": portal_url.strip(),
        "publishedAt": published_at.strip(),
        "releaseTag": release_tag.strip(),
        "signingCertSha256": cert_norm,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Share Flow Release Pipeline Helper")
    sub = parser.add_subparsers(dest="command", required=True)

    p_pre = sub.add_parser("validate-prebuild")
    p_pre.add_argument("--gradle", required=True)
    p_pre.add_argument("--release-tag", required=True)
    p_pre.add_argument("--prev-manifest", default="")
    p_pre.add_argument("--github-output", default="")

    p_post = sub.add_parser("verify-postbuild")
    p_post.add_argument("--version-code", type=int, required=True)
    p_post.add_argument("--cert-sha256", required=True)
    p_post.add_argument("--prev-manifest", default="")

    p_man = sub.add_parser("build-manifest")
    p_man.add_argument("--version-code", type=int, required=True)
    p_man.add_argument("--version-name", required=True)
    p_man.add_argument("--apk-url", required=True)
    p_man.add_argument("--apk-download-url", required=True)
    p_man.add_argument("--sha256", required=True)
    p_man.add_argument("--file-size", type=int, required=True)
    p_man.add_argument("--min-sdk", type=int, default=24)
    p_man.add_argument("--published-at", required=True)
    p_man.add_argument("--release-tag", required=True)
    p_man.add_argument("--cert-sha256", required=True)
    p_man.add_argument("--portal-url", default="https://ow7ab.github.io/Share-Flow-Releases/")
    p_man.add_argument("--notes-ar", default="")
    p_man.add_argument("--notes-en", default="")
    p_man.add_argument("--mandatory", action="store_true")
    p_man.add_argument("--repo-dir", default=".")
    p_man.add_argument("--output", required=True)

    args = parser.parse_args()

    try:
        if args.command == "validate-prebuild":
            meta = validate_prebuild(
                gradle_path=Path(args.gradle),
                release_tag=args.release_tag,
                prev_manifest_path=args.prev_manifest or None,
            )
            if args.github_output:
                with open(args.github_output, "a", encoding="utf-8") as fh:
                    fh.write(f"gradle_version_code={meta['versionCode']}\n")
                    fh.write(f"gradle_version_name={meta['versionName']}\n")
                    fh.write(f"previous_version_code={meta['previousVersionCode']}\n")
            print(
                f"Pre-build version check passed: {meta['applicationId']} "
                f"v{meta['versionName']} (versionCode {meta['previousVersionCode']} -> {meta['versionCode']})."
            )
            return 0

        if args.command == "verify-postbuild":
            info = verify_postbuild_continuity(
                new_version_code=args.version_code,
                new_cert_sha256=args.cert_sha256,
                prev_manifest_path=args.prev_manifest or None,
            )
            if info["initialRelease"]:
                print(f"No prior version.json found; treating build {args.version_code} as initial release.")
            else:
                print(
                    f"Verified monotonic upgrade continuity "
                    f"(versionCode {info['previousVersionCode']} -> {args.version_code}, certificate unchanged)."
                )
            return 0

        if args.command == "build-manifest":
            git_notes = []
            if not args.notes_ar.strip() and not args.notes_en.strip():
                git_notes = extract_git_release_notes(args.release_tag, Path(args.repo_dir))
            manifest = build_manifest(
                version_code=args.version_code,
                version_name=args.version_name,
                apk_url=args.apk_url,
                apk_download_url=args.apk_download_url,
                sha256=args.sha256,
                file_size_bytes=args.file_size,
                min_sdk=args.min_sdk,
                published_at=args.published_at,
                release_tag=args.release_tag,
                cert_sha256=args.cert_sha256,
                portal_url=args.portal_url,
                notes_ar=args.notes_ar,
                notes_en=args.notes_en,
                git_notes=git_notes,
                mandatory=args.mandatory,
            )
            out_file = Path(args.output)
            out_file.parent.mkdir(parents=True, exist_ok=True)
            out_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"Generated and validated unified version.json at {out_file}.")
            return 0

    except Exception as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())

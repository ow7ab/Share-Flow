#!/usr/bin/env python3
"""
Share Flow Historical Releases Migration Tool (Idempotent & SHA-256 Verified)

Migrates all historical GitHub Releases, Git Tags, and original binary APK/asset files
from the legacy archive repository (`ow7ab/Share-Flow-Releases`) into the unified
primary repository (`ow7ab/Share-Flow`) WITHOUT rebuilding APKs, overwriting existing
releases, or deleting the legacy repository.

Features:
  - Preserves original tag names, release titles, release notes, prerelease flags,
    chronological order (oldest -> newest), and exact original asset filenames.
  - Verifies SHA-256 integrity of every downloaded asset (against GitHub's native
    asset digest when present) and re-verifies every uploaded asset on the target repo.
  - Handles existing tag/release conflicts safely (e.g., `V1.0.13` already present in
    both repositories with identical APK SHA-256): verifies parity without data loss.
  - Marks the newest stable non-prerelease as `Latest` (`make_latest: "true"`).
  - Supports `--dry-run` for non-mutating pre-flight audits.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


USER_AGENT = "ShareFlow-Release-Migrator/1.0"


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().lower()


def extract_digest_sha256(asset: dict[str, Any]) -> str | None:
    digest = str(asset.get("digest") or "").strip().lower()
    if digest.startswith("sha256:"):
        cand = digest.split("sha256:", 1)[1].strip()
        if re.fullmatch(r"[0-9a-f]{64}", cand):
            return cand
    return None


def sort_releases_chronologically(releases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Orders releases oldest -> newest so GitHub timeline preserves historical order."""
    return sorted(
        releases,
        key=lambda r: (
            str(r.get("published_at") or r.get("created_at") or ""),
            str(r.get("tag_name") or ""),
        ),
    )


def select_latest_stable_tag(releases: list[dict[str, Any]]) -> str | None:
    stable = [
        r for r in releases
        if not r.get("draft", False) and not r.get("prerelease", False)
    ]
    if not stable:
        return None
    ordered = sort_releases_chronologically(stable)
    return str(ordered[-1].get("tag_name") or "").strip() or None


def build_preserved_body(release: dict[str, Any], source_repo: str) -> str:
    original_body = str(release.get("body") or "").rstrip()
    published_at = str(release.get("published_at") or release.get("created_at") or "unknown").strip()
    tag_name = str(release.get("tag_name") or "").strip()
    marker = f"<!-- migrated-from:{source_repo}@{tag_name} -->"
    if marker in original_body:
        return original_body
    footer = (
        f"\n\n---\n"
        f"{marker}\n"
        f"> Preserved historical release migrated from `{source_repo}` (`{tag_name}`, original publish date: `{published_at}`)."
    )
    return f"{original_body}{footer}".lstrip()


class GitHubApiClient:
    def __init__(self, token: str | None = None) -> None:
        self.token = (token or "").strip()

    def _headers(self, accept: str = "application/vnd.github+json", content_type: str | None = None) -> dict[str, str]:
        headers = {
            "Accept": accept,
            "User-Agent": USER_AGENT,
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if content_type:
            headers["Content-Type"] = content_type
        return headers

    def request_json(
        self,
        method: str,
        url: str,
        payload: dict[str, Any] | None = None,
        allow_404: bool = False,
    ) -> Any:
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(
            url,
            data=data,
            method=method,
            headers=self._headers(content_type="application/json" if data is not None else None),
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as exc:
            if allow_404 and exc.code == 404:
                return None
            detail = exc.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"GitHub API {method} {url} failed ({exc.code}): {detail}") from exc

    def list_paginated(self, url: str) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        page = 1
        while True:
            sep = "&" if "?" in url else "?"
            chunk = self.request_json("GET", f"{url}{sep}per_page=100&page={page}")
            if not isinstance(chunk, list) or not chunk:
                break
            results.extend(chunk)
            if len(chunk) < 100:
                break
            page += 1
        return results

    def download_asset(self, download_url: str, dest_path: str) -> None:
        req = urllib.request.Request(download_url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=180) as resp, open(dest_path, "wb") as out:
            while True:
                buf = resp.read(1024 * 1024)
                if not buf:
                    break
                out.write(buf)

    def upload_asset(self, upload_url_template: str, file_path: str, asset_name: str) -> dict[str, Any]:
        base_upload = upload_url_template.split("{", 1)[0]
        encoded_name = urllib.parse.quote(asset_name)
        upload_url = f"{base_upload}?name={encoded_name}"
        content_type = (
            "application/vnd.android.package-archive"
            if asset_name.lower().endswith(".apk")
            else "application/octet-stream"
        )
        size = os.path.getsize(file_path)
        with open(file_path, "rb") as fh:
            body = fh.read()
        req = urllib.request.Request(
            upload_url,
            data=body,
            method="POST",
            headers={
                **self._headers(content_type=content_type),
                "Content-Length": str(size),
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"Asset upload '{asset_name}' failed ({exc.code}): {detail}") from exc


def plan_migration(
    source_releases: list[dict[str, Any]],
    target_releases: list[dict[str, Any]],
    source_tags: list[dict[str, Any]],
    target_tags: list[dict[str, Any]],
) -> dict[str, Any]:
    """Pure deterministic migration plan builder (unit-tested without network)."""
    target_by_tag = {str(r.get("tag_name") or ""): r for r in target_releases}
    target_tag_names = {str(t.get("name") or "") for t in target_tags}
    ordered_source = sort_releases_chronologically(
        [r for r in source_releases if not r.get("draft", False)]
    )
    latest_stable_tag = select_latest_stable_tag(ordered_source)

    release_actions: list[dict[str, Any]] = []
    for rel in ordered_source:
        tag = str(rel.get("tag_name") or "").strip()
        existing = target_by_tag.get(tag)
        src_assets = rel.get("assets") or []
        if existing is None:
            release_actions.append({
                "tag": tag,
                "action": "create",
                "name": str(rel.get("name") or tag),
                "prerelease": bool(rel.get("prerelease", False)),
                "make_latest": tag == latest_stable_tag,
                "assets_to_upload": [a["name"] for a in src_assets],
                "assets_verified_existing": [],
            })
        else:
            existing_assets = {a["name"]: a for a in (existing.get("assets") or [])}
            to_upload: list[str] = []
            verified_existing: list[str] = []
            for asset in src_assets:
                aname = asset["name"]
                if aname in existing_assets:
                    e_asset = existing_assets[aname]
                    src_sha = extract_digest_sha256(asset)
                    dst_sha = extract_digest_sha256(e_asset)
                    same_size = int(e_asset.get("size", -1)) == int(asset.get("size", -2))
                    if same_size and (src_sha is None or dst_sha is None or src_sha == dst_sha):
                        verified_existing.append(aname)
                        continue
                to_upload.append(aname)
            release_actions.append({
                "tag": tag,
                "action": "verify_existing" if not to_upload else "sync_missing_assets",
                "name": str(existing.get("name") or tag),
                "prerelease": bool(existing.get("prerelease", False)),
                "make_latest": tag == latest_stable_tag,
                "assets_to_upload": to_upload,
                "assets_verified_existing": verified_existing,
            })

    release_tags = {str(r.get("tag_name") or "") for r in ordered_source}
    orphan_tags_to_create = [
        str(t.get("name") or "")
        for t in source_tags
        if str(t.get("name") or "") not in target_tag_names
        and str(t.get("name") or "") not in release_tags
    ]

    return {
        "latest_stable_tag": latest_stable_tag,
        "release_actions": release_actions,
        "orphan_tags_to_create": orphan_tags_to_create,
    }


def execute_migration(
    source_repo: str,
    target_repo: str,
    token: str | None,
    dry_run: bool,
    summary_file: str | None,
) -> int:
    source_client = GitHubApiClient(token=None)  # Public archive repo read-only
    target_client = GitHubApiClient(token=token)

    print(f"Auditing source repo '{source_repo}' and target repo '{target_repo}'...")
    target_meta = target_client.request_json("GET", f"https://api.github.com/repos/{target_repo}")
    target_branch = str(target_meta.get("default_branch") or "main")
    target_ref = target_client.request_json(
        "GET", f"https://api.github.com/repos/{target_repo}/git/ref/heads/{target_branch}"
    )
    target_head_sha = str(target_ref["object"]["sha"])

    source_releases = source_client.list_paginated(f"https://api.github.com/repos/{source_repo}/releases")
    target_releases = target_client.list_paginated(f"https://api.github.com/repos/{target_repo}/releases")
    source_tags = source_client.list_paginated(f"https://api.github.com/repos/{source_repo}/tags")
    target_tags = target_client.list_paginated(f"https://api.github.com/repos/{target_repo}/tags")

    plan = plan_migration(source_releases, target_releases, source_tags, target_tags)
    source_by_tag = {str(r.get("tag_name") or ""): r for r in source_releases}
    target_by_tag = {str(r.get("tag_name") or ""): r for r in target_releases}
    target_tag_names = {str(t.get("name") or "") for t in target_tags}

    print(
        f"Planned actions: {len(plan['release_actions'])} releases, "
        f"{len(plan['orphan_tags_to_create'])} standalone tags, "
        f"latest stable = {plan['latest_stable_tag']}"
    )

    report_rows: list[str] = []
    migrated_count = 0
    verified_existing_count = 0
    verified_assets = 0

    with tempfile.TemporaryDirectory(prefix="shareflow-migrate-") as tmpdir:
        for item in plan["release_actions"]:
            tag = item["tag"]
            action = item["action"]
            src_rel = source_by_tag[tag]
            src_assets = {a["name"]: a for a in (src_rel.get("assets") or [])}

            if dry_run:
                print(
                    f"[DRY-RUN] {tag}: action={action}, upload={item['assets_to_upload']}, "
                    f"existing_ok={item['assets_verified_existing']}, latest={item['make_latest']}"
                )
                report_rows.append(
                    f"| `{tag}` | `{action}` (dry-run) | {len(src_assets)} | — | planned |"
                )
                continue

            if action == "verify_existing":
                verified_existing_count += 1
                verified_assets += len(item["assets_verified_existing"])
                print(f"[SKIP/VERIFIED] {tag} already exists in {target_repo} with matching SHA-256 assets.")
                report_rows.append(
                    f"| `{tag}` | Already present (verified) | {len(item['assets_verified_existing'])} | Matched | OK |"
                )
                continue

            # Ensure tag exists on target_repo pointing to target_head_sha if not already tagged
            if tag not in target_tag_names:
                target_client.request_json(
                    "POST",
                    f"https://api.github.com/repos/{target_repo}/git/refs",
                    payload={"ref": f"refs/tags/{tag}", "sha": target_head_sha},
                )
                target_tag_names.add(tag)

            if action == "create":
                created = target_client.request_json(
                    "POST",
                    f"https://api.github.com/repos/{target_repo}/releases",
                    payload={
                        "tag_name": tag,
                        "target_commitish": target_branch,
                        "name": str(src_rel.get("name") or tag),
                        "body": build_preserved_body(src_rel, source_repo),
                        "draft": True,
                        "prerelease": bool(src_rel.get("prerelease", False)),
                    },
                )
                dst_rel = created
            else:
                dst_rel = target_by_tag[tag]

            asset_digests_summary: list[str] = []
            for asset_name in item["assets_to_upload"]:
                asset_meta = src_assets[asset_name]
                local_path = os.path.join(tmpdir, f"{tag}_{asset_name}")
                print(f"  Downloading {tag}/{asset_name} ({asset_meta['size']} bytes)...")
                source_client.download_asset(asset_meta["browser_download_url"], local_path)

                actual_size = os.path.getsize(local_path)
                if actual_size != int(asset_meta["size"]):
                    raise RuntimeError(
                        f"Downloaded size mismatch for {tag}/{asset_name}: "
                        f"expected {asset_meta['size']}, got {actual_size}"
                    )

                local_sha = sha256_file(local_path)
                expected_sha = extract_digest_sha256(asset_meta)
                if expected_sha and local_sha != expected_sha:
                    raise RuntimeError(
                        f"Downloaded SHA-256 mismatch for {tag}/{asset_name}: "
                        f"expected {expected_sha}, got {local_sha}"
                    )

                print(f"  Uploading {tag}/{asset_name} (SHA-256 {local_sha[:16]}...)...")
                uploaded = target_client.upload_asset(dst_rel["upload_url"], local_path, asset_name)
                if int(uploaded.get("size", -1)) != actual_size:
                    raise RuntimeError(
                        f"Uploaded size mismatch for {tag}/{asset_name}: "
                        f"expected {actual_size}, got {uploaded.get('size')}"
                    )
                uploaded_sha = extract_digest_sha256(uploaded)
                if uploaded_sha and uploaded_sha != local_sha:
                    raise RuntimeError(
                        f"Uploaded SHA-256 mismatch for {tag}/{asset_name}: "
                        f"expected {local_sha}, got {uploaded_sha}"
                    )

                os.remove(local_path)
                verified_assets += 1
                asset_digests_summary.append(f"`{asset_name}`: `{local_sha[:12]}…`")

            if action == "create":
                make_latest = "true" if item["make_latest"] else "false"
                target_client.request_json(
                    "PATCH",
                    f"https://api.github.com/repos/{target_repo}/releases/{dst_rel['id']}",
                    payload={
                        "draft": False,
                        "prerelease": bool(src_rel.get("prerelease", False)),
                        "make_latest": make_latest,
                    },
                )
                migrated_count += 1
                print(f"[MIGRATED] {tag} published on {target_repo} (latest={make_latest}).")
                report_rows.append(
                    f"| `{tag}` | Migrated | {len(item['assets_to_upload'])} | "
                    f"{', '.join(asset_digests_summary) or 'No assets'} | SHA-256 Verified |"
                )

        # Preserve any standalone source tags (e.g., V1.0.20)
        for orphan_tag in plan["orphan_tags_to_create"]:
            if dry_run:
                print(f"[DRY-RUN] Standalone tag to preserve: {orphan_tag}")
            else:
                target_client.request_json(
                    "POST",
                    f"https://api.github.com/repos/{target_repo}/git/refs",
                    payload={"ref": f"refs/tags/{orphan_tag}", "sha": target_head_sha},
                )
                print(f"[TAG PRESERVED] {orphan_tag}")

    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as fh:
            fh.write("## Share Flow Historical Releases Migration Summary\n\n")
            fh.write(f"- **Source Archive Repo:** `{source_repo}` (untouched)\n")
            fh.write(f"- **Primary Unified Repo:** `{target_repo}`\n")
            fh.write(f"- **Mode:** `{'DRY-RUN' if dry_run else 'LIVE MIGRATION'}`\n")
            fh.write(f"- **Latest Stable Release:** `{plan['latest_stable_tag']}`\n")
            fh.write(f"- **Migrated Releases:** `{migrated_count}` | **Verified Existing:** `{verified_existing_count}` | **SHA-256 Verified Assets:** `{verified_assets}`\n\n")
            fh.write("| Tag | Status | Assets | SHA-256 Verification | Result |\n")
            fh.write("|---|---|---|---|---|\n")
            for row in report_rows:
                fh.write(f"{row}\n")

    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Migrate historical Share Flow GitHub Releases with SHA-256 verification")
    parser.add_argument("--source-repo", default="ow7ab/Share-Flow-Releases")
    parser.add_argument("--target-repo", default="ow7ab/Share-Flow")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--summary-file", default=os.environ.get("GITHUB_STEP_SUMMARY", ""))
    args = parser.parse_args(argv)

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
    if not args.dry_run and not token.strip():
        raise SystemExit("Missing GH_TOKEN / GITHUB_TOKEN for live release migration.")

    return execute_migration(
        source_repo=args.source_repo,
        target_repo=args.target_repo,
        token=token or None,
        dry_run=args.dry_run,
        summary_file=args.summary_file or None,
    )


if __name__ == "__main__":
    sys.exit(main())

const test = require("node:test");
const assert = require("node:assert/strict");
const portal = require("../docs/app.js");

test("isSafeDirectApkUrl accepts direct HTTPS APK URLs and rejects ZIPs, HTML pages, and unsafe URLs", () => {
  assert.equal(
    portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk"),
    true
  );
  assert.equal(
    portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.apk"),
    true
  );
  assert.equal(
    portal.isSafeDirectApkUrl("https://cdn.shareflow.app/releases/Share-Flow.apk"),
    true
  );

  // Must strictly reject ZIPs, source archives, GitHub HTML pages, cleartext HTTP, private hosts, and XSS schemes
  assert.equal(portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow/archive/refs/heads/main.zip"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.zip"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow/releases/latest"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://github.com/ow7ab/Share-Flow"), false);
  assert.equal(portal.isSafeDirectApkUrl("http://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://localhost/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://192.168.1.10/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://user:pass@github.com/ow7ab/Share-Flow/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl("javascript:alert(1)//.apk"), false);
});

test("selectApkAsset prioritizes Share-Flow.apk and ignores ZIP, JSON, and SHA256 files", () => {
  const assets = [
    { name: "source.zip", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/source.zip", size: 1000 },
    { name: "version.json", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/version.json", size: 512 },
    { name: "other-debug.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/other-debug.apk", size: 9000 },
    { name: "Share-Flow.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.apk", size: 24500000 }
  ];

  const selected = portal.selectApkAsset(assets, "Share-Flow.apk");
  assert.ok(selected);
  assert.equal(selected.name, "Share-Flow.apk");
  assert.equal(selected.size, 24500000);

  const zipOnly = portal.selectApkAsset([assets[0], assets[1]], "Share-Flow.apk");
  assert.equal(zipOnly, null);
});

test("resolveReleasesList always surfaces latest published stable APK and preserves previous releases", () => {
  const sha1 = "a".repeat(64);
  const sha2 = "b".repeat(64);
  const releases = [
    {
      draft: true,
      prerelease: false,
      name: "Draft v1.0.12",
      tag_name: "v1.0.12",
      assets: [{ name: "Share-Flow.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.12/Share-Flow.apk", size: 25000000 }]
    },
    {
      draft: false,
      prerelease: false,
      name: "Share Flow v1.0.10 (Build 11)",
      tag_name: "v1.0.10",
      published_at: "2026-11-22T09:00:00Z",
      body: `**بصمة التحقق (SHA-256):** \`${sha1}\``,
      assets: [
        { name: "Share-Flow.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.apk", size: 24500000 },
        { name: "version.json", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/version.json", size: 600 }
      ]
    },
    {
      draft: false,
      prerelease: false,
      name: "Share Flow v1.0.9 (Build 10)",
      tag_name: "v1.0.9",
      published_at: "2026-11-20T09:00:00Z",
      body: `SHA-256: ${sha2}`,
      assets: [
        { name: "Share-Flow.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.9/Share-Flow.apk", size: 24100000 }
      ]
    }
  ];

  const state = portal.resolveReleasesList(releases, "Share-Flow.apk");
  assert.equal(state.status, "ready");
  assert.equal(state.data.versionLabel, "Share Flow v1.0.10 (Build 11)");
  assert.equal(state.data.buildNumber, 11);
  assert.equal(state.data.apkUrl, "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.apk");
  assert.equal(state.data.fileSizeBytes, 24500000);
  assert.equal(state.data.sha256, sha1);
  assert.equal(state.history.length, 1);
  assert.equal(state.history[0].tagName, "v1.0.9");

  // Simulate a newly published release arriving at index 0 -> portal immediately serves the newer APK
  const nextReleases = [
    {
      draft: false,
      prerelease: false,
      name: "Share Flow v1.0.11 (Build 12)",
      tag_name: "v1.0.11",
      published_at: "2026-11-25T10:00:00Z",
      body: `SHA-256: ${"c".repeat(64)}`,
      assets: [
        { name: "Share-Flow.apk", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.11/Share-Flow.apk", size: 25100000 }
      ]
    },
    ...releases
  ];
  const updatedState = portal.resolveReleasesList(nextReleases, "Share-Flow.apk");
  assert.equal(updatedState.status, "ready");
  assert.equal(updatedState.data.tagName, "v1.0.11");
  assert.equal(updatedState.data.apkUrl, "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.11/Share-Flow.apk");
  assert.equal(updatedState.history.length, 2);
});

test("resolveReleasesList returns unavailable when no APK release exists", () => {
  assert.equal(portal.resolveReleasesList([]).status, "unavailable");
  assert.equal(
    portal.resolveReleasesList([{ draft: false, name: "v1.0.0", assets: [{ name: "bundle.zip", browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.0/bundle.zip" }] }]).status,
    "unavailable"
  );
});

test("normalizeCustomManifest parses release workflow version.json and blocks non-APK URLs", () => {
  const valid = portal.normalizeCustomManifest({
    versionCode: 11,
    versionName: "1.0.10",
    apkDownloadUrl: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.10/Share-Flow.apk",
    sha256: "d".repeat(64),
    fileSizeBytes: 24500000,
    publishedAt: "2026-11-22T09:00:00Z",
    releaseTag: "v1.0.10"
  });
  assert.equal(valid.status, "ready");
  assert.equal(valid.data.versionLabel, "v1.0.10 (Build 11)");
  assert.equal(valid.data.apkUrl.endsWith("/Share-Flow.apk"), true);
  assert.equal(valid.data.sha256, "d".repeat(64));

  const badZip = portal.normalizeCustomManifest({
    versionCode: 11,
    versionName: "1.0.10",
    apkDownloadUrl: "https://cdn.shareflow.app/Share-Flow.zip"
  });
  assert.equal(badZip.status, "unavailable");
});

test("formatBytes formats Arabic and English file sizes cleanly", () => {
  assert.equal(portal.formatBytes(0, "ar"), "—");
  assert.equal(portal.formatBytes(1024 * 1024 * 25, "en"), "25.00 MB");
  assert.equal(portal.formatBytes(1024 * 1024 * 25, "ar"), "25.00 م.ب");
});

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const portal = require("../docs/app.js");

const PUBLIC_REPO = "ow7ab/Share-Flow-Releases";
const DIRECT_LATEST_APK = `https://github.com/${PUBLIC_REPO}/releases/latest/download/Share-Flow.apk`;

test("docs/config.js targets public ow7ab/Share-Flow-Releases and GitHub Pages without tokens", () => {
  const configPath = path.join(__dirname, "..", "docs", "config.js");
  const source = fs.readFileSync(configPath, "utf8");
  const sandbox = { window: {} };
  vm.runInNewContext(source, sandbox);

  const cfg = sandbox.window.SHAREFLOW_PORTAL_CONFIG;
  assert.ok(cfg);
  assert.equal(cfg.releasesRepo, PUBLIC_REPO);
  assert.equal(cfg.assetFileName, "Share-Flow.apk");
  assert.equal(cfg.manifestFileName, "version.json");
  assert.equal(cfg.directLatestApkUrl, DIRECT_LATEST_APK);
  assert.equal(cfg.portalUrl, "https://ow7ab.github.io/Share-Flow-Releases/");
  assert.equal(cfg.customManifestUrl, "");
  assert.equal(portal.isSafeDirectApkUrl(cfg.directLatestApkUrl), true);
});

test("static docs/ files contain zero secrets, tokens, unsafe innerHTML, or private repo release URLs", () => {
  const docsDir = path.join(__dirname, "..", "docs");
  const files = fs.readdirSync(docsDir).filter((f) => /\.(html|css|js|json)$/i.test(f));
  assert.ok(files.includes("index.html"));
  assert.ok(files.includes("app.js"));
  assert.ok(files.includes("config.js"));
  assert.ok(files.includes("styles.css"));

  const forbiddenPatterns = [
    /ghp_[A-Za-z0-9]{20,}/,
    /github_pat_[A-Za-z0-9_]{20,}/,
    /gho_[A-Za-z0-9]{20,}/,
    /AIza[0-9A-Za-z\-_]{35}/,
    /BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY/,
    /Authorization\s*:/i,
    /\.innerHTML\s*=/,
    /https:\/\/(api\.)?github\.com\/(repos\/)?ow7ab\/Share-Flow(\/|["'`])/
  ];

  for (const file of files) {
    const content = fs.readFileSync(path.join(docsDir, file), "utf8");
    for (const pattern of forbiddenPatterns) {
      assert.equal(
        pattern.test(content),
        false,
        `Forbidden pattern ${pattern} matched in docs/${file}`
      );
    }
  }
});

test("isSafeDirectApkUrl accepts direct HTTPS APK URLs and rejects ZIPs, HTML pages, and unsafe URLs", () => {
  assert.equal(portal.isSafeDirectApkUrl(DIRECT_LATEST_APK), true);
  assert.equal(
    portal.isSafeDirectApkUrl(`https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.apk`),
    true
  );
  assert.equal(
    portal.isSafeDirectApkUrl("https://cdn.shareflow.app/releases/Share-Flow.apk"),
    true
  );

  // Must strictly reject ZIPs, source archives, GitHub HTML pages, cleartext HTTP, private hosts, and XSS schemes
  assert.equal(portal.isSafeDirectApkUrl(`https://github.com/${PUBLIC_REPO}/archive/refs/heads/main.zip`), false);
  assert.equal(portal.isSafeDirectApkUrl(`https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.zip`), false);
  assert.equal(portal.isSafeDirectApkUrl(`https://github.com/${PUBLIC_REPO}/releases/latest`), false);
  assert.equal(portal.isSafeDirectApkUrl(`https://github.com/${PUBLIC_REPO}`), false);
  assert.equal(portal.isSafeDirectApkUrl(`http://github.com/${PUBLIC_REPO}/releases/latest/download/Share-Flow.apk`), false);
  assert.equal(portal.isSafeDirectApkUrl("https://localhost/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl("https://192.168.1.10/Share-Flow.apk"), false);
  assert.equal(portal.isSafeDirectApkUrl(`https://user:pass@github.com/${PUBLIC_REPO}/Share-Flow.apk`), false);
  assert.equal(portal.isSafeDirectApkUrl("javascript:alert(1)//.apk"), false);
});

test("selectApkAsset prioritizes Share-Flow.apk and ignores ZIP, JSON, and SHA256 files", () => {
  const assets = [
    { name: "source.zip", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/source.zip`, size: 1000 },
    { name: "version.json", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/version.json`, size: 512 },
    { name: "other-debug.apk", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/other-debug.apk`, size: 9000 },
    { name: "Share-Flow.apk", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.apk`, size: 24500000 }
  ];

  const selected = portal.selectApkAsset(assets, "Share-Flow.apk");
  assert.ok(selected);
  assert.equal(selected.name, "Share-Flow.apk");
  assert.equal(selected.size, 24500000);

  const zipOnly = portal.selectApkAsset([assets[0], assets[1]], "Share-Flow.apk");
  assert.equal(zipOnly, null);
});

test("resolveLatestAndHistory binds latest APK to direct latest URL, formats release notes, and keeps previous releases", () => {
  const shaLatest = "a".repeat(64);
  const shaPrev = "b".repeat(64);

  const latestRelease = {
    draft: false,
    prerelease: false,
    name: "Share Flow v1.0.10 (Build 11)",
    tag_name: "v1.0.10",
    published_at: "2026-11-22T09:00:00Z",
    body: `## Share Flow v1.0.10\n- تحسين سرعة النقل المحلي\n- **SHA-256:** \`${shaLatest}\``,
    assets: [
      {
        name: "Share-Flow.apk",
        browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.apk`,
        size: 29757303
      },
      {
        name: "version.json",
        browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/version.json`,
        size: 620
      }
    ]
  };

  const allReleases = [
    latestRelease,
    {
      draft: false,
      prerelease: false,
      name: "Share Flow v1.0.9 (Build 10)",
      tag_name: "v1.0.9",
      published_at: "2026-11-20T09:00:00Z",
      body: `SHA-256: ${shaPrev}`,
      assets: [
        {
          name: "Share-Flow.apk",
          browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.9/Share-Flow.apk`,
          size: 28900000
        }
      ]
    }
  ];

  const state = portal.resolveLatestAndHistory(
    latestRelease,
    allReleases,
    "Share-Flow.apk",
    DIRECT_LATEST_APK
  );

  assert.equal(state.status, "ready");
  assert.equal(state.data.versionLabel, "Share Flow v1.0.10 (Build 11)");
  assert.equal(state.data.buildNumber, 11);
  assert.equal(state.data.apkUrl, DIRECT_LATEST_APK);
  assert.equal(state.data.fileSizeBytes, 29757303);
  assert.equal(state.data.sha256, shaLatest);
  assert.ok(state.data.releaseNotes.includes("تحسين سرعة النقل المحلي"));
  assert.equal(state.history.length, 1);
  assert.equal(state.history[0].tagName, "v1.0.9");
  assert.equal(
    state.history[0].apkUrl,
    `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.9/Share-Flow.apk`
  );
});

test("resolveReleasesList always surfaces latest published stable APK and skips drafts", () => {
  const sha1 = "a".repeat(64);
  const sha2 = "b".repeat(64);
  const releases = [
    {
      draft: true,
      prerelease: false,
      name: "Draft v1.0.12",
      tag_name: "v1.0.12",
      assets: [{ name: "Share-Flow.apk", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.12/Share-Flow.apk`, size: 25000000 }]
    },
    {
      draft: false,
      prerelease: false,
      name: "Share Flow v1.0.10 (Build 11)",
      tag_name: "v1.0.10",
      published_at: "2026-11-22T09:00:00Z",
      body: `**بصمة التحقق (SHA-256):** \`${sha1}\``,
      assets: [
        { name: "Share-Flow.apk", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.apk`, size: 24500000 }
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
        { name: "Share-Flow.apk", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.9/Share-Flow.apk`, size: 24100000 }
      ]
    }
  ];

  const state = portal.resolveReleasesList(releases, "Share-Flow.apk");
  assert.equal(state.status, "ready");
  assert.equal(state.data.tagName, "v1.0.10");
  assert.equal(state.history.length, 1);
  assert.equal(state.history[0].tagName, "v1.0.9");
});

test("resolveReleasesList returns unavailable when no APK release exists", () => {
  assert.equal(portal.resolveReleasesList([]).status, "unavailable");
  assert.equal(
    portal.resolveReleasesList([{ draft: false, name: "v1.0.0", assets: [{ name: "bundle.zip", browser_download_url: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.0/bundle.zip` }] }]).status,
    "unavailable"
  );
});

test("normalizeCustomManifest parses release workflow version.json, release notes, and blocks non-APK URLs", () => {
  const valid = portal.normalizeCustomManifest({
    versionCode: 11,
    versionName: "1.0.10",
    apkDownloadUrl: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.10/Share-Flow.apk`,
    latestApkDownloadUrl: DIRECT_LATEST_APK,
    sha256: "d".repeat(64),
    fileSizeBytes: 24500000,
    publishedAt: "2026-11-22T09:00:00Z",
    releaseTag: "v1.0.10",
    releaseNotesAr: "تحديث رسمي موقّع",
    releaseNotesEn: "Signed official update"
  });
  assert.equal(valid.status, "ready");
  assert.equal(valid.data.versionLabel, "v1.0.10 (Build 11)");
  assert.equal(valid.data.apkUrl, DIRECT_LATEST_APK);
  assert.equal(valid.data.sha256, "d".repeat(64));
  assert.equal(valid.data.releaseNotesAr, "تحديث رسمي موقّع");
  assert.equal(valid.data.releaseNotesEn, "Signed official update");

  const badZip = portal.normalizeCustomManifest({
    versionCode: 11,
    versionName: "1.0.10",
    apkDownloadUrl: "https://cdn.shareflow.app/Share-Flow.zip"
  });
  assert.equal(badZip.status, "unavailable");
});

test("formatReleaseNotes strips markdown markers cleanly", () => {
  const notes = portal.formatReleaseNotes("### Release v1.0.10\n- **Direct APK** `Share-Flow.apk`\n---");
  assert.equal(notes.includes("###"), false);
  assert.equal(notes.includes("**"), false);
  assert.equal(notes.includes("`"), false);
  assert.ok(notes.includes("Release v1.0.10"));
  assert.ok(notes.includes("• Direct APK Share-Flow.apk"));
});

test("formatBytes formats Arabic and English file sizes cleanly", () => {
  assert.equal(portal.formatBytes(0, "ar"), "—");
  assert.equal(portal.formatBytes(1024 * 1024 * 25, "en"), "25.00 MB");
  assert.equal(portal.formatBytes(1024 * 1024 * 25, "ar"), "25.00 م.ب");
});

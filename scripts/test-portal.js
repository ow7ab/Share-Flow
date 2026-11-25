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
  assert.equal(
    cfg.officialVersionManifestUrl,
    "https://ow7ab.github.io/Share-Flow-Releases/version.json"
  );
  assert.equal(cfg.customManifestUrl, "");
  assert.equal(portal.isSafeDirectApkUrl(cfg.directLatestApkUrl), true);

  assert.ok(Array.isArray(cfg.galleryItems) && cfg.galleryItems.length >= 2);
  const docsDir = path.join(__dirname, "..", "docs");
  for (const item of cfg.galleryItems) {
    assert.ok(item.src && !item.src.includes(".."), `Invalid gallery src: ${item.src}`);
    const assetPath = path.join(docsDir, item.src);
    assert.ok(fs.existsSync(assetPath), `Gallery asset missing on disk: docs/${item.src}`);
    assert.ok(fs.statSync(assetPath).size > 0, `Gallery asset empty: docs/${item.src}`);
  }
});

test("docs/index.html contains all official landing page sections, AboutSection placement, theme toggle, and scroll progress bar", () => {
  const html = fs.readFileSync(path.join(__dirname, "..", "docs", "index.html"), "utf8");
  for (const requiredId of [
    'id="scroll-progress-bar"',
    'id="scroll-progress-fill"',
    'id="theme-toggle"',
    'id="download-apk-btn"',
    'id="discover-features-btn"',
    'id="features"',
    'id="about"',
    'data-component="AboutSection"',
    'id="screenshots"',
    'id="install-title"',
    'id="releases"',
    'id="faq"',
    'id="lightbox"'
  ]) {
    assert.ok(html.includes(requiredId), `Missing required element ${requiredId} in docs/index.html`);
  }

  // Verify AboutSection is placed strictly between Features (#features) and Visual Gallery (#screenshots)
  const featuresIdx = html.indexOf('id="features"');
  const aboutIdx = html.indexOf('id="about"');
  const galleryIdx = html.indexOf('id="screenshots"');
  assert.ok(featuresIdx > 0 && aboutIdx > featuresIdx && galleryIdx > aboutIdx, "AboutSection must appear between #features and #screenshots");
});

test("theme toggle supports Deep Ruby dark default and High-Contrast light CSS variables", () => {
  assert.equal(portal.resolveInitialTheme(null), "dark");
  assert.equal(portal.resolveInitialTheme("dark"), "dark");
  assert.equal(portal.resolveInitialTheme("light"), "light");
  assert.equal(portal.resolveInitialTheme("invalid"), "dark");
  assert.equal(portal.getNextTheme("dark"), "light");
  assert.equal(portal.getNextTheme("light"), "dark");

  const css = fs.readFileSync(path.join(__dirname, "..", "docs", "styles.css"), "utf8");
  assert.ok(css.includes('html[data-theme="light"]'), "Missing html[data-theme=\"light\"] CSS variable block");
  assert.ok(css.includes("--surface-canvas: #FFF9F2;"), "Expected high-contrast light surface token");
  assert.ok(css.includes("--text-primary: #210812;"), "Expected high-contrast dark burgundy text token for light theme");
});

test("calculateScrollProgress clamps scroll ratio accurately across all viewport states", () => {
  assert.equal(portal.calculateScrollProgress(0, 2000, 800), 0);
  assert.equal(portal.calculateScrollProgress(600, 2000, 800), 0.5);
  assert.equal(portal.calculateScrollProgress(1200, 2000, 800), 1);
  assert.equal(portal.calculateScrollProgress(-50, 2000, 800), 0);
  assert.equal(portal.calculateScrollProgress(2500, 2000, 800), 1);
  assert.equal(portal.calculateScrollProgress(0, 800, 800), 0);
  assert.equal(portal.calculateScrollProgress(0, 600, 900), 0);
});

test("gallery implements lazy loading, safe asset validation, and theme-aware skeleton loading state", () => {
  assert.equal(portal.isValidGalleryAssetSrc("assets/shareflow-icon.png"), true);
  assert.equal(portal.isValidGalleryAssetSrc("screenshots/home.webp"), true);
  assert.equal(portal.isValidGalleryAssetSrc("../secrets.png"), false);
  assert.equal(portal.isValidGalleryAssetSrc("javascript:alert(1).png"), false);
  assert.equal(portal.isValidGalleryAssetSrc("https://evil.example/x.png"), false);
  assert.equal(portal.isValidGalleryAssetSrc("assets/script.js"), false);

  assert.deepEqual(portal.createGalleryImageState("assets/shareflow-hero.jpg", false), {
    valid: true,
    shouldDefer: true,
    initialState: "idle"
  });
  assert.deepEqual(portal.createGalleryImageState("assets/shareflow-hero.jpg", true), {
    valid: true,
    shouldDefer: false,
    initialState: "loaded"
  });
  assert.deepEqual(portal.createGalleryImageState("../bad.png", false), {
    valid: false,
    shouldDefer: false,
    initialState: "error"
  });

  const html = fs.readFileSync(path.join(__dirname, "..", "docs", "index.html"), "utf8");
  const css = fs.readFileSync(path.join(__dirname, "..", "docs", "styles.css"), "utf8");
  const js = fs.readFileSync(path.join(__dirname, "..", "docs", "app.js"), "utf8");

  assert.ok(html.includes("gallery-skeleton-card"), "Expected initial gallery skeleton placeholder cards in docs/index.html");
  assert.ok(html.includes("gallery-skeleton-shimmer"), "Expected shimmer element inside gallery skeleton placeholder");
  assert.ok(css.includes("@keyframes gallery-shimmer"), "Expected gallery-shimmer keyframes in docs/styles.css");
  assert.ok(css.includes("--skeleton-base:") && css.includes("--skeleton-shine:"), "Expected theme-aware skeleton CSS variables");
  assert.ok(js.includes("IntersectionObserver"), "Expected IntersectionObserver lazy loading in docs/app.js");
  assert.ok(js.includes('img.loading = "lazy"') && js.includes('img.decoding = "async"'), "Expected native lazy loading and async decoding");
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

  const unifiedArrayManifest = portal.normalizeCustomManifest({
    versionCode: 12,
    versionName: "1.0.11",
    apkUrl: `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.11/Share-Flow.apk`,
    sha256: "e".repeat(64),
    fileSizeBytes: 26200000,
    publishedAt: "2026-11-22T10:00:00Z",
    releaseNotes: [
      "واجهة الموقع الرسمي بنمط Liquid Glass",
      "دعم التحديث التلقائي المباشر عبر version.json"
    ]
  });
  assert.equal(unifiedArrayManifest.status, "ready");
  assert.equal(unifiedArrayManifest.data.versionLabel, "v1.0.11 (Build 12)");
  assert.equal(
    unifiedArrayManifest.data.apkUrl,
    `https://github.com/${PUBLIC_REPO}/releases/download/v1.0.11/Share-Flow.apk`
  );
  assert.deepEqual(portal.toChangelogLines(unifiedArrayManifest.data.releaseNotes), [
    "واجهة الموقع الرسمي بنمط Liquid Glass",
    "دعم التحديث التلقائي المباشر عبر version.json"
  ]);

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

test("official Share Flow 4-color branding variables and frameless contain icon rules are enforced with zero legacy palette remnants", () => {
  const docsDir = path.join(__dirname, "..", "docs");
  const css = fs.readFileSync(path.join(docsDir, "styles.css"), "utf8");
  const html = fs.readFileSync(path.join(docsDir, "index.html"), "utf8");

  for (const requiredToken of [
    "--color-primary: #CA2851;",
    "--color-secondary: #FF6766;",
    "--color-accent: #FFB173;",
    "--color-light: #FFE3B3;"
  ]) {
    assert.ok(css.includes(requiredToken), `Missing official brand CSS variable: ${requiredToken}`);
  }

  assert.ok(html.includes('content="#CA2851"'), "Expected theme-color meta tag to use #CA2851");
  assert.ok(html.includes('href="assets/favicon.png"'), "Expected favicon link to reference assets/favicon.png");
  assert.ok(css.includes(".brand-logo") && css.includes("object-fit: contain;"), "Logo must use object-fit: contain");

  // Strictly forbid any legacy palette hex or RGB remnants across all docs/ code files
  const legacyPalettePatterns = [
    /#0a1821/i,
    /#1d59bb/i,
    /#76b9ff/i,
    /#fef3b3/i,
    /#febd19/i,
    /rgba\(\s*15\s*,\s*34\s*,\s*48/i,
    /rgba\(\s*118\s*,\s*185\s*,\s*255/i
  ];
  for (const file of ["index.html", "styles.css", "app.js", "config.js"]) {
    const content = fs.readFileSync(path.join(docsDir, file), "utf8");
    for (const pattern of legacyPalettePatterns) {
      assert.equal(pattern.test(content), false, `Legacy palette color ${pattern} found in docs/${file}`);
    }
  }

  // Verify PNG magic signature on both shareflow-icon.png (apple-touch-icon) and favicon.png
  const pngSig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  for (const imgRel of ["assets/shareflow-icon.png", "assets/favicon.png"]) {
    const buf = fs.readFileSync(path.join(docsDir, imgRel));
    assert.ok(buf.subarray(0, 8).equals(pngSig), `Expected valid PNG signature for docs/${imgRel}`);
  }

  // Verify optimized WebP magic bytes (RIFF....WEBP) and docs/index.html references
  assert.ok(html.includes('src="assets/shareflow-hero.webp"'), "Expected hero backdrop to reference assets/shareflow-hero.webp");
  assert.ok(html.includes('src="assets/shareflow-icon.webp"'), "Expected inline logos to reference assets/shareflow-icon.webp");
  for (const webpRel of ["assets/shareflow-hero.webp", "assets/shareflow-icon.webp"]) {
    const buf = fs.readFileSync(path.join(docsDir, webpRel));
    assert.equal(buf.subarray(0, 4).toString("ascii"), "RIFF", `Expected RIFF header for docs/${webpRel}`);
    assert.equal(buf.subarray(8, 12).toString("ascii"), "WEBP", `Expected WEBP header for docs/${webpRel}`);
  }
});

test("ContactSection includes all four official personal contact links with safe attributes and bilingual support", () => {
  const docsDir = path.join(__dirname, "..", "docs");
  const html = fs.readFileSync(path.join(docsDir, "index.html"), "utf8");
  const css = fs.readFileSync(path.join(docsDir, "styles.css"), "utf8");
  const js = fs.readFileSync(path.join(docsDir, "app.js"), "utf8");

  for (const expectedSnippet of [
    'id="contact"',
    'data-component="ContactSection"',
    'id="contact-heading">تواصل معي<',
    'id="contact-lead">يسعدني تواصلك معي عبر القنوات التالية<',
    'href="https://www.facebook.com/ow7ab"',
    'href="https://www.linkedin.com/in/ow7ab/"',
    'href="https://wa.me/249963003301"',
    'href="mailto:oaw7ab@gmail.com"',
    'rel="noopener noreferrer"'
  ]) {
    assert.ok(html.includes(expectedSnippet), `Missing ContactSection element in docs/index.html: ${expectedSnippet}`);
  }

  assert.ok(css.includes(".contact-grid") && css.includes(".contact-card"), "Missing ContactSection styles in docs/styles.css");
  assert.ok(js.includes("renderContactSection") && js.includes('contactHeading: "Contact Me"'), "Missing bilingual ContactSection support in docs/app.js");
});

test("gallery enforces per-image low-priority lazy loading and smooth GPU-composited Lightbox open/close transitions", () => {
  const lazyCfg = portal.getGalleryLazyConfig();
  assert.equal(lazyCfg.loading, "lazy");
  assert.equal(lazyCfg.decoding, "async");
  assert.equal(lazyCfg.fetchPriority, "low");
  assert.equal(lazyCfg.threshold, 0.01);

  const docsDir = path.join(__dirname, "..", "docs");
  const html = fs.readFileSync(path.join(docsDir, "index.html"), "utf8");
  const css = fs.readFileSync(path.join(docsDir, "styles.css"), "utf8");
  const js = fs.readFileSync(path.join(docsDir, "app.js"), "utf8");

  assert.ok(html.includes('id="lightbox-stage"'), "Expected lightbox-stage container in docs/index.html");
  assert.ok(html.includes('aria-hidden="true"'), "Expected aria-hidden initial state on lightbox in docs/index.html");
  assert.ok(html.includes('loading="lazy"'), "Expected lazy loading attribute on below-the-fold static assets in docs/index.html");

  for (const selector of [
    "body.lightbox-open",
    ".lightbox.is-open",
    ".lightbox.is-open .lightbox-dialog",
    ".lightbox-img.is-ready",
    ".lightbox-img.is-switching",
    "@media (prefers-reduced-motion: reduce)"
  ]) {
    assert.ok(css.includes(selector), `Missing smooth Lightbox CSS rule: ${selector}`);
  }

  assert.ok(js.includes('setAttribute("fetchpriority"'), "Expected per-image fetchpriority attribute in docs/app.js");
  assert.ok(js.includes("revealLightboxImage") && js.includes('modal.classList.add("is-open")'), "Expected smooth animated Lightbox open orchestration in docs/app.js");
});




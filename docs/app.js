(function () {
  "use strict";

  const CONFIG = (typeof window !== "undefined" && window.SHAREFLOW_PORTAL_CONFIG) || {
    releasesRepo: "ow7ab/Share-Flow",
    assetFileName: "Share-Flow.apk",
    manifestFileName: "version.json",
    customManifestUrl: ""
  };

  const I18N = {
    ar: {
      dir: "rtl",
      langBtn: "English",
      brandSub: "مشاركة ملفات محلية فائقة السرعة • بلا إنترنت",
      badge: "البوابة الرسمية للتحميل المباشر (APK)",
      heroTitle: "حمّل أحدث إصدار من تطبيق Share Flow لأندرويد",
      heroDesc:
        "تطبيق أندرويد متكامل لمشاركة الصور والفيديوهات والتطبيقات والملفات بسرعة فائقة عبر شبكة Wi-Fi المحلية ورموز QR مباشرة دون الحاجة إلى إنترنت أو خوادم وسيطة.",
      loadingStatus: "جاري التحقق من أحدث إصدار رسمي موقّع (APK)…",
      readyStatus: "أحدث إصدار رسمي موقّع وجاهز للتثبيت المباشر (Share-Flow.apk)",
      unavailableStatus:
        "لا يوجد إصدار APK منشور للعامة حالياً. فور نشر أول إصدار عبر GitHub Releases سيظهر زر التحميل المباشر تلقائياً هنا.",
      privateHint:
        "ملاحظة: إذا كان المستودع خاصاً، تأكد من ربط بوابة التحميل بمساحة تنزيل عامة مستقلة (مثل مستودع إصدارات عام أو رابط version.json مخصص).",
      rateLimitStatus:
        "تم بلوغ حد الطلبات المؤقت لخادم الإصدارات من هذه الشبكة. أعد المحاولة بعد قليل.",
      errorStatus:
        "تعذّر الاتصال بخادم الإصدارات حالياً. تحقق من اتصال الإنترنت ثم أعد المحاولة.",
      downloadDirect: "تحميل Share-Flow.apk مباشرة",
      downloadWaiting: "جاري تجهيز رابط التحميل…",
      downloadUnavailable: "التحميل غير متاح حالياً",
      retryBtn: "إعادة الفحص",
      copyShaBtn: "نسخ SHA-256",
      copiedShaBtn: "تم نسخ SHA-256",
      metaVersion: "رقم الإصدار",
      metaSize: "حجم ملف APK",
      metaUpdated: "تاريخ آخر تحديث",
      metaPackage: "صيغة الملف",
      metaSha: "بصمة التحقق SHA-256",
      apkFormatValue: "Share-Flow.apk (تثبيت مباشر بدون ZIP)",
      featuresTitle: "مميزات Share Flow",
      f1: "ربط مباشر عبر QR ونقطة اتصال محلية بدون راوتر أو إنترنت.",
      f2: "تشفير AES-256-GCM والتحقق من سلامة الملفات عبر SHA-256.",
      f3: "مشاركة التطبيقات والصور والفيديوهات والمستندات والمجلدات.",
      f4: "خزنة خاصة مشفّرة، عارض وسائط مدمج، وفحص تحديثات سحابي آمن.",
      installTitle: "خطوات التثبيت الآمن على أندرويد",
      i1: "اضغط زر التحميل المباشر لتنزيل ملف Share-Flow.apk مباشرة دون فك ضغط.",
      i2: "افتح ملف Share-Flow.apk بعد اكتمال التحميل.",
      i3: "اسمح بتثبيت التطبيقات من هذا المصدر عند طلب نظام Android.",
      i4: "تُحفظ بياناتك وإصداراتك السابقة بأمان عند التحديث فوق النسخة الأقدم.",
      historyTitle: "سجل الإصدارات السابقة",
      historyEmpty: "سيظهر سجل الإصدارات السابقة فور نشر أكثر من إصدار.",
      historyDownload: "تحميل Share-Flow.apk",
      footer: "Share Flow — ملفاتك على أجهزتك وتحت تحكمك الكامل."
    },
    en: {
      dir: "ltr",
      langBtn: "العربية",
      brandSub: "Fast Offline Local File Sharing • Android",
      badge: "Official Direct APK Download Portal",
      heroTitle: "Download the Latest Share Flow APK for Android",
      heroDesc:
        "Fast, encrypted offline file sharing for photos, videos, apps, and documents over direct local Wi-Fi and QR pairing — no internet or intermediary servers required.",
      loadingStatus: "Checking latest signed official APK release…",
      readyStatus: "Latest signed release verified and ready for direct install (Share-Flow.apk)",
      unavailableStatus:
        "No public APK release is available yet. Once the first release is published via GitHub Releases, the direct APK download button will activate automatically.",
      privateHint:
        "Note: If the source repository is private, connect this portal to a public releases mirror repository or a standalone HTTPS version.json endpoint.",
      rateLimitStatus:
        "Temporary release server rate limit reached from this network. Please retry shortly.",
      errorStatus:
        "Unable to reach the release server right now. Check your internet connection and retry.",
      downloadDirect: "Download Share-Flow.apk Directly",
      downloadWaiting: "Preparing direct download…",
      downloadUnavailable: "Download Currently Unavailable",
      retryBtn: "Check Again",
      copyShaBtn: "Copy SHA-256",
      copiedShaBtn: "SHA-256 Copied",
      metaVersion: "Version",
      metaSize: "APK Size",
      metaUpdated: "Last Updated",
      metaPackage: "Package Format",
      metaSha: "SHA-256 Checksum",
      apkFormatValue: "Share-Flow.apk (Direct install, no ZIP)",
      featuresTitle: "Key Features",
      f1: "Direct QR & local hotspot pairing with zero router or internet required.",
      f2: "AES-256-GCM transport encryption with SHA-256 integrity checks.",
      f3: "Share apps, original-quality photos, videos, documents, and folders.",
      f4: "Encrypted Private Vault, built-in media viewer, and verified cloud updates.",
      installTitle: "Safe Android Installation Steps",
      i1: "Tap the direct download button to download Share-Flow.apk directly (no ZIP extraction).",
      i2: "Open Share-Flow.apk once the download completes.",
      i3: "Allow installation from this source when prompted by Android.",
      i4: "Consistent release signing preserves your data seamlessly across updates.",
      historyTitle: "Previous Releases",
      historyEmpty: "Previous releases will appear here once published.",
      historyDownload: "Download Share-Flow.apk",
      footer: "Share Flow — Your Files. Your Devices. Your Control."
    }
  };

  let currentLang = "ar";
  let latestReleaseState = { status: "loading", data: null, history: [] };

  function isPrivateOrLoopbackHost(hostname) {
    const clean = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
    if (!clean || clean === "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local")) {
      return true;
    }
    if (
      clean === "::1" ||
      clean === "::" ||
      clean.startsWith("::ffff:") ||
      clean.startsWith("fe80:") ||
      clean.startsWith("fc") ||
      clean.startsWith("fd")
    ) {
      return true;
    }
    const parts = clean.split(".");
    if (parts.length === 4 && parts.every((p) => /^\d+$/.test(p) && Number(p) >= 0 && Number(p) <= 255)) {
      const a = Number(parts[0]);
      const b = Number(parts[1]);
      return (
        a === 10 ||
        a === 127 ||
        a === 0 ||
        (a === 100 && b >= 64 && b <= 127) ||
        (a === 169 && b === 254) ||
        (a === 172 && b >= 16 && b <= 31) ||
        (a === 192 && b === 168)
      );
    }
    return false;
  }

  function isSafeDirectApkUrl(rawUrl) {
    if (typeof rawUrl !== "string") return false;
    const trimmed = rawUrl.trim();
    if (!trimmed || trimmed.length > 512) return false;
    try {
      const parsed = new URL(trimmed);
      if (parsed.protocol !== "https:") return false;
      if (parsed.username || parsed.password) return false;
      if (!parsed.hostname || !parsed.hostname.includes(".")) return false;
      if (isPrivateOrLoopbackHost(parsed.hostname)) return false;
      const pathLower = parsed.pathname.toLowerCase();
      if (!pathLower.endsWith(".apk")) return false;
      if (
        pathLower.endsWith(".zip") ||
        pathLower.includes("/archive/") ||
        pathLower.includes("/zipball/") ||
        pathLower.includes("/tarball/")
      ) {
        return false;
      }
      return true;
    } catch {
      return false;
    }
  }

  function isSafeHttpsEndpoint(rawUrl) {
    if (typeof rawUrl !== "string") return false;
    try {
      const parsed = new URL(rawUrl.trim());
      if (parsed.protocol !== "https:") return false;
      if (parsed.username || parsed.password) return false;
      if (!parsed.hostname || !parsed.hostname.includes(".")) return false;
      return !isPrivateOrLoopbackHost(parsed.hostname);
    } catch {
      return false;
    }
  }

  function formatBytes(bytes, lang) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "—";
    const units = lang === "ar" ? ["بايت", "ك.ب", "م.ب", "ج.ب"] : ["B", "KB", "MB", "GB"];
    let value = bytes;
    let idx = 0;
    while (value >= 1024 && idx < units.length - 1) {
      value /= 1024;
      idx++;
    }
    return `${value.toFixed(idx === 0 ? 0 : 2)} ${units[idx]}`;
  }

  function formatDate(isoString, lang) {
    if (!isoString) return "—";
    const date = new Date(isoString);
    if (Number.isNaN(date.getTime())) return "—";
    return new Intl.DateTimeFormat(lang === "ar" ? "ar-SD" : "en-US", {
      year: "numeric",
      month: "long",
      day: "numeric"
    }).format(date);
  }

  function extractShaFromNotes(body) {
    if (typeof body !== "string") return "";
    const labeled = body.match(/SHA-256[^0-9a-fA-F]*([0-9a-fA-F]{64})\b/i);
    if (labeled) return labeled[1].toLowerCase();
    const match = body.match(/\b[0-9a-fA-F]{64}\b/);
    return match ? match[0].toLowerCase() : "";
  }

  function extractBuildNumber(title, body) {
    const combined = `${title || ""}\n${body || ""}`;
    const match = combined.match(/\bBuild\s+(\d+)\b/i) || combined.match(/بناء\s+(\d+)/);
    return match ? Number(match[1]) : null;
  }

  function selectApkAsset(assets, preferredFileName) {
    if (!Array.isArray(assets)) return null;
    const expectedName = preferredFileName || CONFIG.assetFileName || "Share-Flow.apk";
    const exact = assets.find(
      (a) => a && a.name === expectedName && isSafeDirectApkUrl(a.browser_download_url)
    );
    if (exact) return exact;
    return (
      assets.find(
        (a) =>
          a &&
          typeof a.name === "string" &&
          a.name.toLowerCase().endsWith(".apk") &&
          isSafeDirectApkUrl(a.browser_download_url)
      ) || null
    );
  }

  function resolveReleasesList(releases, preferredFileName) {
    if (!Array.isArray(releases) || releases.length === 0) {
      return { status: "unavailable", data: null, history: [] };
    }

    const mapped = releases
      .filter((r) => r && !r.draft)
      .map((r) => {
        const apkAsset = selectApkAsset(r.assets, preferredFileName);
        if (!apkAsset) return null;
        const buildNumber = extractBuildNumber(r.name, r.body);
        const baseLabel = r.name || r.tag_name || "Latest";
        return {
          versionLabel: baseLabel,
          tagName: r.tag_name || "",
          buildNumber,
          prerelease: Boolean(r.prerelease),
          apkUrl: apkAsset.browser_download_url,
          fileSizeBytes: Number(apkAsset.size) || 0,
          publishedAt: r.published_at || apkAsset.updated_at || "",
          sha256: extractShaFromNotes(r.body)
        };
      })
      .filter(Boolean);

    if (mapped.length === 0) {
      return { status: "unavailable", data: null, history: [] };
    }

    const stableIndex = mapped.findIndex((r) => !r.prerelease);
    const primaryIndex = stableIndex >= 0 ? stableIndex : 0;
    const primary = mapped[primaryIndex];
    const history = mapped.filter((_, idx) => idx !== primaryIndex);

    return {
      status: "ready",
      data: primary,
      history
    };
  }

  function normalizeCustomManifest(data) {
    if (!data || typeof data !== "object") {
      return { status: "unavailable", data: null, history: [] };
    }
    if (!isSafeDirectApkUrl(data.apkDownloadUrl)) {
      return { status: "unavailable", data: null, history: [] };
    }
    const versionName = typeof data.versionName === "string" && data.versionName.trim()
      ? data.versionName.trim()
      : "latest";
    const versionCode = Number(data.versionCode) > 0 ? Number(data.versionCode) : null;
    const label = versionCode ? `v${versionName} (Build ${versionCode})` : `v${versionName}`;
    return {
      status: "ready",
      data: {
        versionLabel: label,
        tagName: typeof data.releaseTag === "string" ? data.releaseTag : "",
        buildNumber: versionCode,
        prerelease: false,
        apkUrl: data.apkDownloadUrl.trim(),
        fileSizeBytes: Number(data.fileSizeBytes) || 0,
        publishedAt: typeof data.publishedAt === "string" ? data.publishedAt : "",
        sha256: typeof data.sha256 === "string" && /^[0-9a-fA-F]{64}$/.test(data.sha256.trim())
          ? data.sha256.trim().toLowerCase()
          : ""
      },
      history: []
    };
  }

  function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
  }

  function applyStaticStrings() {
    const t = I18N[currentLang];
    document.documentElement.lang = currentLang;
    document.documentElement.dir = t.dir;

    setText("lang-toggle", t.langBtn);
    setText("brand-subtitle", t.brandSub);
    setText("sudan-badge", t.badge);
    setText("hero-title", t.heroTitle);
    setText("hero-desc", t.heroDesc);
    setText("retry-btn", t.retryBtn);
    setText("meta-version-label", t.metaVersion);
    setText("meta-size-label", t.metaSize);
    setText("meta-updated-label", t.metaUpdated);
    setText("meta-package-label", t.metaPackage);
    setText("meta-package-value", t.apkFormatValue);
    setText("meta-sha-label", t.metaSha);
    setText("features-title", t.featuresTitle);
    setText("feat-1", t.f1);
    setText("feat-2", t.f2);
    setText("feat-3", t.f3);
    setText("feat-4", t.f4);
    setText("install-title", t.installTitle);
    setText("inst-1", t.i1);
    setText("inst-2", t.i2);
    setText("inst-3", t.i3);
    setText("inst-4", t.i4);
    setText("history-title", t.historyTitle);
    setText("footer-text", t.footer);
    setText("copy-sha-btn", t.copyShaBtn);
  }

  function renderReleaseState() {
    const t = I18N[currentLang];
    const banner = document.getElementById("status-banner");
    const spinner = document.getElementById("status-spinner");
    const statusText = document.getElementById("status-text");
    const downloadBtn = document.getElementById("download-apk-btn");
    const retryBtn = document.getElementById("retry-btn");
    const shaCard = document.getElementById("meta-sha-card");
    const copyShaBtn = document.getElementById("copy-sha-btn");
    const privateNote = document.getElementById("private-hint");

    banner.className = "status-banner";
    privateNote.classList.add("hidden");

    if (latestReleaseState.status === "loading") {
      banner.classList.add("status-loading");
      spinner.classList.remove("hidden");
      statusText.textContent = t.loadingStatus;
      downloadBtn.textContent = t.downloadWaiting;
      downloadBtn.removeAttribute("href");
      downloadBtn.setAttribute("aria-disabled", "true");
      retryBtn.classList.add("hidden");
      shaCard.classList.add("hidden");
      copyShaBtn.classList.add("hidden");
      setText("meta-version-value", "…");
      setText("meta-size-value", "…");
      setText("meta-updated-value", "…");
    } else if (latestReleaseState.status === "ready" && latestReleaseState.data) {
      const rel = latestReleaseState.data;
      banner.classList.add("status-ready");
      spinner.classList.add("hidden");
      statusText.textContent = t.readyStatus;
      downloadBtn.textContent = `${t.downloadDirect} (${rel.versionLabel})`;
      downloadBtn.setAttribute("href", rel.apkUrl);
      downloadBtn.setAttribute("download", CONFIG.assetFileName || "Share-Flow.apk");
      downloadBtn.removeAttribute("aria-disabled");
      retryBtn.classList.add("hidden");

      setText("meta-version-value", rel.versionLabel);
      setText("meta-size-value", formatBytes(rel.fileSizeBytes, currentLang));
      setText("meta-updated-value", formatDate(rel.publishedAt, currentLang));

      if (rel.sha256) {
        shaCard.classList.remove("hidden");
        copyShaBtn.classList.remove("hidden");
        setText("meta-sha-value", rel.sha256);
      } else {
        shaCard.classList.add("hidden");
        copyShaBtn.classList.add("hidden");
      }
    } else if (latestReleaseState.status === "unavailable") {
      banner.classList.add("status-unavailable");
      spinner.classList.add("hidden");
      statusText.textContent = t.unavailableStatus;
      privateNote.textContent = t.privateHint;
      privateNote.classList.remove("hidden");
      downloadBtn.textContent = t.downloadUnavailable;
      downloadBtn.removeAttribute("href");
      downloadBtn.setAttribute("aria-disabled", "true");
      retryBtn.classList.remove("hidden");
      shaCard.classList.add("hidden");
      copyShaBtn.classList.add("hidden");
      setText("meta-version-value", "—");
      setText("meta-size-value", "—");
      setText("meta-updated-value", "—");
    } else {
      banner.classList.add("status-error");
      spinner.classList.add("hidden");
      statusText.textContent =
        latestReleaseState.status === "rate_limited" ? t.rateLimitStatus : t.errorStatus;
      downloadBtn.textContent = t.downloadUnavailable;
      downloadBtn.removeAttribute("href");
      downloadBtn.setAttribute("aria-disabled", "true");
      retryBtn.classList.remove("hidden");
      shaCard.classList.add("hidden");
      copyShaBtn.classList.add("hidden");
      setText("meta-version-value", "—");
      setText("meta-size-value", "—");
      setText("meta-updated-value", "—");
    }

    renderHistory();
  }

  function renderHistory() {
    const t = I18N[currentLang];
    const container = document.getElementById("history-list");
    if (!container) return;
    while (container.firstChild) {
      container.removeChild(container.firstChild);
    }

    const items = latestReleaseState.history || [];
    if (items.length === 0) {
      const empty = document.createElement("p");
      empty.className = "brand-subtitle";
      empty.textContent = t.historyEmpty;
      container.appendChild(empty);
      return;
    }

    items.forEach((rel) => {
      if (!isSafeDirectApkUrl(rel.apkUrl)) return;
      const row = document.createElement("div");
      row.className = "history-row";

      const info = document.createElement("div");
      const title = document.createElement("strong");
      title.textContent = rel.versionLabel;
      const meta = document.createElement("div");
      meta.className = "brand-subtitle";
      meta.textContent = `${formatBytes(rel.fileSizeBytes, currentLang)} • ${formatDate(rel.publishedAt, currentLang)}`;
      info.appendChild(title);
      info.appendChild(meta);

      const link = document.createElement("a");
      link.className = "history-link";
      link.setAttribute("href", rel.apkUrl);
      link.setAttribute("download", CONFIG.assetFileName || "Share-Flow.apk");
      link.setAttribute("rel", "noopener noreferrer");
      link.textContent = t.historyDownload;

      row.appendChild(info);
      row.appendChild(link);
      container.appendChild(row);
    });
  }

  async function fetchJsonWithTimeout(url, headers, timeoutMs) {
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
    try {
      const res = await fetch(url, {
        headers,
        cache: "no-store",
        signal: controller ? controller.signal : undefined
      });
      return res;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async function fetchFromCustomManifest(manifestUrl) {
    const res = await fetchJsonWithTimeout(
      manifestUrl,
      { Accept: "application/json" },
      12000
    );
    if (!res.ok) throw new Error(`HTTP_${res.status}`);
    const data = await res.json();
    return normalizeCustomManifest(data);
  }

  async function trySameOriginFallbackManifest() {
    try {
      const res = await fetchJsonWithTimeout(
        "./latest-release.json",
        { Accept: "application/json" },
        5000
      );
      if (!res.ok) return null;
      const data = await res.json();
      const normalized = normalizeCustomManifest(data);
      return normalized.status === "ready" ? normalized : null;
    } catch {
      return null;
    }
  }

  async function fetchLatestRelease() {
    latestReleaseState = { status: "loading", data: null, history: [] };
    renderReleaseState();

    try {
      if (CONFIG.customManifestUrl && isSafeHttpsEndpoint(CONFIG.customManifestUrl)) {
        latestReleaseState = await fetchFromCustomManifest(CONFIG.customManifestUrl);
        renderReleaseState();
        return;
      }

      const apiUrl = `https://api.github.com/repos/${CONFIG.releasesRepo}/releases?per_page=10`;
      const response = await fetchJsonWithTimeout(
        apiUrl,
        { Accept: "application/vnd.github+json" },
        12000
      );

      if (response.status === 404) {
        latestReleaseState = { status: "unavailable", data: null, history: [] };
        renderReleaseState();
        return;
      }

      if (response.status === 403 || response.status === 429) {
        const fallback = await trySameOriginFallbackManifest();
        latestReleaseState = fallback || { status: "rate_limited", data: null, history: [] };
        renderReleaseState();
        return;
      }

      if (!response.ok) {
        throw new Error(`HTTP_${response.status}`);
      }

      const releases = await response.json();
      latestReleaseState = resolveReleasesList(releases, CONFIG.assetFileName);
      renderReleaseState();
    } catch {
      const fallback = await trySameOriginFallbackManifest();
      latestReleaseState = fallback || { status: "error", data: null, history: [] };
      renderReleaseState();
    }
  }

  function init() {
    applyStaticStrings();

    const langBtn = document.getElementById("lang-toggle");
    if (langBtn) {
      langBtn.addEventListener("click", () => {
        currentLang = currentLang === "ar" ? "en" : "ar";
        applyStaticStrings();
        renderReleaseState();
      });
    }

    const retryBtn = document.getElementById("retry-btn");
    if (retryBtn) {
      retryBtn.addEventListener("click", fetchLatestRelease);
    }

    const copyShaBtn = document.getElementById("copy-sha-btn");
    if (copyShaBtn) {
      copyShaBtn.addEventListener("click", async () => {
        const sha = latestReleaseState.data && latestReleaseState.data.sha256;
        if (!sha || !navigator.clipboard) return;
        try {
          await navigator.clipboard.writeText(sha);
          copyShaBtn.textContent = I18N[currentLang].copiedShaBtn;
          setTimeout(() => {
            copyShaBtn.textContent = I18N[currentLang].copyShaBtn;
          }, 2000);
        } catch {
          // Clipboard permission unavailable
        }
      });
    }

    fetchLatestRelease();
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      isSafeDirectApkUrl,
      isSafeHttpsEndpoint,
      selectApkAsset,
      formatBytes,
      extractShaFromNotes,
      extractBuildNumber,
      resolveReleasesList,
      normalizeCustomManifest
    };
  } else {
    document.addEventListener("DOMContentLoaded", init);
  }
})();

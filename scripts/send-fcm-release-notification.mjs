#!/usr/bin/env node
/**
 * Share Flow — Automated GitHub Release -> FCM HTTP v1 Push Notifier
 *
 * Sends a single high-priority Firebase Cloud Messaging (FCM HTTP v1) topic push
 * to `share_flow_updates` whenever a verified public Share Flow release is published.
 *
 * Security & Reliability Guarantees:
 * - Zero external npm dependencies (uses Node.js 20+ built-in `node:crypto` & `fetch`).
 * - Authenticates via Google OAuth 2.0 RS256 Service Account JWT (`FIREBASE_SERVICE_ACCOUNT_JSON`).
 * - Never prints credentials, private keys, or OAuth bearer tokens to stdout/stderr.
 * - Rejects draft/prerelease releases (unless explicitly overridden), cleartext HTTP URLs,
 *   private/loopback URLs, missing `.apk` assets, or missing/invalid SHA-256 checksums.
 * - Enforces cross-run idempotency via `--dedupe-marker-file` so workflow reruns never spam users.
 * - Uses bounded exponential backoff retries (max 3 attempts) on transient HTTP 429/5xx/network failures.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const DEFAULT_FCM_TOPIC = "share_flow_updates";
export const DEFAULT_FCM_CHANNEL_ID = "share_flow_updates_channel";
export const DEFAULT_CLICK_ACTION = "OPEN_SHAREFLOW_UPDATES";
export const DEFAULT_BRAND_COLOR = "#CA2851";
export const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
export const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

const SHA256_REGEX = /^[0-9a-f]{64}$/i;
const MAX_RELEASE_NOTES_CHARS = 1400;
const MAX_RETRY_ATTEMPTS = 3;

function base64UrlEncode(input) {
  const buf = Buffer.isBuffer(input) ? input : Buffer.from(String(input), "utf8");
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function isValidPublicHttpsUrl(rawUrl) {
  if (typeof rawUrl !== "string" || !rawUrl.trim()) return false;
  let parsed;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    return false;
  }
  if (parsed.protocol !== "https:") return false;
  if (parsed.username || parsed.password) return false;
  const host = (parsed.hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
  if (!host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")) {
    return false;
  }
  if (isPrivateOrLoopbackHost(host)) return false;
  return true;
}

function isPrivateOrLoopbackHost(host) {
  if (host.includes(":")) {
    if (
      host === "::1" ||
      host === "::" ||
      host.startsWith("fc") ||
      host.startsWith("fd") ||
      host.startsWith("ff") ||
      /^fe[89ab]/.test(host)
    ) {
      return true;
    }
    if (host.startsWith("::ffff:")) {
      return isPrivateOrLoopbackHost(host.slice("::ffff:".length));
    }
  }
  const ipv4Match = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!ipv4Match) return false;
  const parts = ipv4Match.slice(1).map((n) => Number(n));
  if (parts.some((n) => n < 0 || n > 255)) return true;
  const [a, b, c] = parts;
  if (a === 10 || a === 127 || a === 0 || a >= 224) return true;
  if (a === 169 && b === 254) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 0 && c === 0) return true;
  if (a === 192 && b === 168) return true;
  if (a === 198 && (b === 18 || b === 19)) return true;
  if (a === 100 && b >= 64 && b <= 127) return true;
  return false;
}

export function normalizeVersionName(raw) {
  return String(raw || "").trim().replace(/^[vV]+/, "");
}

export function extractSha256FromDigestOrText(text) {
  if (!text || typeof text !== "string") return null;
  const digestMatch = text.match(/sha256:([0-9a-f]{64})/i);
  if (digestMatch) return digestMatch[1].toLowerCase();
  const explicitMatch = text.match(/APK\s+SHA-256[^0-9a-fA-F]*([0-9a-fA-F]{64})/i);
  if (explicitMatch) return explicitMatch[1].toLowerCase();
  const standalone = text.trim();
  if (SHA256_REGEX.test(standalone)) return standalone.toLowerCase();
  const anyHash = text.match(/\b([0-9a-f]{64})\b/i);
  return anyHash ? anyHash[1].toLowerCase() : null;
}

export function deriveVersionCodeFromTagOrName(versionName, releaseBody = "") {
  const buildMatch = String(releaseBody || "").match(/Build\s+(\d{1,9})/i);
  if (buildMatch) {
    const parsed = Number.parseInt(buildMatch[1], 10);
    if (Number.isInteger(parsed) && parsed > 0) return parsed;
  }
  const clean = normalizeVersionName(versionName);
  const parts = clean.split("-")[0].split(".").map((p) => Number.parseInt(p, 10));
  if (parts.length >= 2 && parts.every((n) => Number.isInteger(n) && n >= 0)) {
    const major = parts[0] || 0;
    const minor = parts[1] || 0;
    const patch = parts[2] || 0;
    const derived = major * 10000 + minor * 100 + patch;
    if (derived > 0) return derived;
  }
  return 0;
}

/**
 * Normalizes and validates release metadata from either a GitHub Releases API JSON object
 * or explicit CLI arguments / version.json manifest.
 */
export function resolveAndValidateReleasePayload({
  releaseJson = null,
  manifestJson = null,
  versionName = "",
  versionCode = 0,
  apkUrl = "",
  sha256 = "",
  fileSizeBytes = 0,
  publishedAt = "",
  releaseNotes = "",
  releaseTag = "",
  allowPrerelease = false,
} = {}) {
  if (releaseJson) {
    if (releaseJson.draft === true) {
      throw new Error("Refusing to send FCM notification for a draft GitHub Release.");
    }
    if (releaseJson.prerelease === true && !allowPrerelease) {
      throw new Error("Refusing to send FCM notification for a pre-release GitHub Release.");
    }
  }

  const assets = Array.isArray(releaseJson?.assets) ? releaseJson.assets : [];
  const apkAsset =
    assets.find((a) => typeof a?.name === "string" && a.name.toLowerCase() === "share-flow.apk") ||
    assets.find((a) => typeof a?.name === "string" && a.name.toLowerCase().endsWith(".apk"));

  const resolvedTag =
    String(releaseTag || releaseJson?.tag_name || manifestJson?.releaseTag || "").trim() ||
    (versionName ? `v${normalizeVersionName(versionName)}` : "");

  const resolvedVersionName = normalizeVersionName(
    versionName || manifestJson?.versionName || resolvedTag
  );
  if (!resolvedVersionName) {
    throw new Error("Missing valid versionName for release notification.");
  }

  const resolvedVersionCode = Number(
    versionCode ||
      manifestJson?.versionCode ||
      deriveVersionCodeFromTagOrName(resolvedVersionName, `${releaseJson?.name || ""} ${releaseJson?.body || ""}`)
  );
  if (!Number.isInteger(resolvedVersionCode) || resolvedVersionCode <= 0) {
    throw new Error(`Invalid or missing positive versionCode (got: ${resolvedVersionCode}).`);
  }

  const resolvedApkUrl = String(
    apkUrl ||
      manifestJson?.apkDownloadUrl ||
      manifestJson?.apkUrl ||
      apkAsset?.browser_download_url ||
      ""
  ).trim();
  if (!isValidPublicHttpsUrl(resolvedApkUrl) || !resolvedApkUrl.toLowerCase().includes(".apk")) {
    throw new Error(`Invalid or non-HTTPS APK download URL: "${resolvedApkUrl}"`);
  }

  const resolvedSha256 =
    extractSha256FromDigestOrText(sha256) ||
    extractSha256FromDigestOrText(manifestJson?.sha256) ||
    extractSha256FromDigestOrText(apkAsset?.digest) ||
    extractSha256FromDigestOrText(releaseJson?.body);

  if (!resolvedSha256 || !SHA256_REGEX.test(resolvedSha256)) {
    throw new Error("Missing or invalid 64-character hex APK SHA-256 checksum.");
  }

  const resolvedFileSize = Number(
    fileSizeBytes || manifestJson?.fileSizeBytes || apkAsset?.size || 0
  );
  if (!Number.isFinite(resolvedFileSize) || resolvedFileSize < 0) {
    throw new Error(`Invalid fileSizeBytes: ${resolvedFileSize}`);
  }

  const resolvedPublishedAt = String(
    publishedAt || manifestJson?.publishedAt || releaseJson?.published_at || new Date().toISOString()
  ).trim();

  const rawNotes = String(
    releaseNotes ||
      manifestJson?.releaseNotesAr ||
      manifestJson?.releaseNotesEn ||
      (Array.isArray(manifestJson?.releaseNotes) ? manifestJson.releaseNotes.join("\n") : "") ||
      releaseJson?.body ||
      ""
  ).trim();

  const sanitizedNotes = rawNotes
    .replace(/\r\n/g, "\n")
    .slice(0, MAX_RELEASE_NOTES_CHARS)
    .trim();

  return {
    releaseTag: resolvedTag || `v${resolvedVersionName}`,
    versionName: resolvedVersionName,
    versionCode: resolvedVersionCode,
    apkUrl: resolvedApkUrl,
    sha256: resolvedSha256.toLowerCase(),
    fileSizeBytes: Math.trunc(resolvedFileSize),
    publishedAt: resolvedPublishedAt,
    releaseNotes: sanitizedNotes,
  };
}

export function buildFcmHttpV1Message(releaseMeta, topic = DEFAULT_FCM_TOPIC) {
  const safeTopic = String(topic || DEFAULT_FCM_TOPIC).trim();
  if (!/^[a-zA-Z0-9-_.~%]+$/.test(safeTopic)) {
    throw new Error(`Invalid FCM topic name: "${safeTopic}"`);
  }

  const title = "تحديث جديد متوفر | Share Flow";
  const body = `يتوفر الآن إصدار جديد من Share Flow (v${releaseMeta.versionName}). حدّث تطبيقك للاستفادة من المميزات الجديدة والتحسينات.`;

  return {
    message: {
      topic: safeTopic,
      notification: {
        title,
        body,
      },
      android: {
        priority: "HIGH",
        notification: {
          channel_id: DEFAULT_FCM_CHANNEL_ID,
          click_action: DEFAULT_CLICK_ACTION,
          color: DEFAULT_BRAND_COLOR,
          tag: `shareflow_update_${releaseMeta.versionCode}`,
        },
      },
      data: {
        type: "app_update",
        versionName: String(releaseMeta.versionName),
        versionCode: String(releaseMeta.versionCode),
        apkUrl: String(releaseMeta.apkUrl),
        sha256: String(releaseMeta.sha256),
        fileSizeBytes: String(releaseMeta.fileSizeBytes),
        publishedAt: String(releaseMeta.publishedAt),
        releaseNotes: String(releaseMeta.releaseNotes || ""),
        targetRoute: "updates",
        releaseTag: String(releaseMeta.releaseTag),
      },
    },
  };
}

export function createServiceAccountJwt(serviceAccount, nowEpochSeconds = Math.floor(Date.now() / 1000)) {
  if (!serviceAccount || typeof serviceAccount !== "object") {
    throw new Error("Firebase service account JSON must be a valid JSON object.");
  }
  const clientEmail = String(serviceAccount.client_email || "").trim();
  const privateKey = String(serviceAccount.private_key || "").replace(/\\n/g, "\n").trim();
  const tokenUri = String(serviceAccount.token_uri || DEFAULT_TOKEN_URI).trim();
  const projectId = String(serviceAccount.project_id || "").trim();

  if (!clientEmail || !privateKey || !projectId) {
    throw new Error("Firebase service account JSON is missing client_email, private_key, or project_id.");
  }

  const header = { alg: "RS256", typ: "JWT" };
  const claimSet = {
    iss: clientEmail,
    sub: clientEmail,
    aud: tokenUri,
    scope: FCM_SCOPE,
    iat: nowEpochSeconds,
    exp: nowEpochSeconds + 3600,
  };

  const signingInput = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(claimSet))}`;
  const signature = crypto.sign("RSA-SHA256", Buffer.from(signingInput, "utf8"), privateKey);
  return `${signingInput}.${base64UrlEncode(signature)}`;
}

async function fetchWithRetry(url, options, { maxAttempts = MAX_RETRY_ATTEMPTS, baseDelayMs = 400 } = {}) {
  let lastError = null;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const response = await fetch(url, options);
      if (response.ok) {
        return response;
      }
      const isRetryable = response.status === 429 || response.status >= 500;
      const bodyText = await response.text().catch(() => "");
      if (!isRetryable || attempt === maxAttempts) {
        const sanitizedBody = bodyText.slice(0, 300);
        throw new Error(`HTTP ${response.status} from endpoint: ${sanitizedBody}`);
      }
      const retryAfterHeader = response.headers.get("retry-after");
      const retryAfterSec = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : NaN;
      const waitMs =
        Number.isFinite(retryAfterSec) && retryAfterSec > 0
          ? Math.min(retryAfterSec * 1000, 10000)
          : baseDelayMs * Math.pow(2, attempt - 1);
      await new Promise((resolve) => setTimeout(resolve, waitMs));
    } catch (err) {
      lastError = err;
      if (attempt === maxAttempts || /HTTP 4\d\d/.test(String(err?.message || "")) && !/HTTP 429/.test(String(err?.message || ""))) {
        throw err;
      }
      await new Promise((resolve) => setTimeout(resolve, baseDelayMs * Math.pow(2, attempt - 1)));
    }
  }
  throw lastError || new Error("Request failed after retries");
}

export async function obtainAccessToken(serviceAccount, { tokenEndpoint = null, retryDelayMs = 400 } = {}) {
  const effectiveTokenUrl = tokenEndpoint || serviceAccount.token_uri || DEFAULT_TOKEN_URI;
  const assertion = createServiceAccountJwt({
    ...serviceAccount,
    token_uri: effectiveTokenUrl,
  });

  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion,
  }).toString();

  const response = await fetchWithRetry(
    effectiveTokenUrl,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body,
    },
    { baseDelayMs: retryDelayMs }
  );

  const json = await response.json();
  if (!json?.access_token) {
    throw new Error("OAuth2 token endpoint returned no access_token.");
  }
  return String(json.access_token);
}

export async function sendFcmReleaseNotification({
  serviceAccount,
  releaseMeta,
  topic = DEFAULT_FCM_TOPIC,
  dryRun = false,
  dedupeMarkerFile = "",
  tokenEndpoint = null,
  fcmEndpoint = null,
  retryDelayMs = 400,
} = {}) {
  const dedupeKey = `${releaseMeta.releaseTag}#${releaseMeta.versionCode}`;
  const dedupeState = readDedupeMarker(dedupeMarkerFile);
  if (dedupeState.sentKeys.includes(dedupeKey)) {
    return {
      status: "skipped_duplicate",
      dedupeKey,
      messageId: null,
      payload: buildFcmHttpV1Message(releaseMeta, topic),
    };
  }

  const payload = buildFcmHttpV1Message(releaseMeta, topic);

  if (dryRun) {
    return {
      status: "dry_run",
      dedupeKey,
      messageId: null,
      payload,
    };
  }

  const projectId = String(serviceAccount?.project_id || "").trim();
  if (!projectId) {
    throw new Error("Missing project_id in Firebase service account JSON.");
  }

  const accessToken = await obtainAccessToken(serviceAccount, {
    tokenEndpoint,
    retryDelayMs,
  });

  const endpoint =
    fcmEndpoint || `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`;

  const response = await fetchWithRetry(
    endpoint,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json; charset=UTF-8",
      },
      body: JSON.stringify(payload),
    },
    { baseDelayMs: retryDelayMs }
  );

  const responseJson = await response.json().catch(() => ({}));
  writeDedupeMarker(dedupeMarkerFile, [...dedupeState.sentKeys, dedupeKey]);

  return {
    status: "sent",
    dedupeKey,
    messageId: responseJson?.name || null,
    payload,
  };
}

function readDedupeMarker(markerFile) {
  if (!markerFile || !fs.existsSync(markerFile)) {
    return { sentKeys: [] };
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(markerFile, "utf8"));
    const sentKeys = Array.isArray(parsed?.sentKeys) ? parsed.sentKeys.map(String) : [];
    return { sentKeys };
  } catch {
    return { sentKeys: [] };
  }
}

function writeDedupeMarker(markerFile, sentKeys) {
  if (!markerFile) return;
  const dir = path.dirname(markerFile);
  if (dir) fs.mkdirSync(dir, { recursive: true });
  const unique = Array.from(new Set(sentKeys)).slice(-50);
  fs.writeFileSync(
    markerFile,
    JSON.stringify({ sentKeys: unique, updatedAt: new Date().toISOString() }, null, 2),
    "utf8"
  );
}

function parseCliArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    if (key === "dry-run" || key === "allow-prerelease") {
      args[key] = true;
    } else if (i + 1 < argv.length) {
      args[key] = argv[++i];
    }
  }
  return args;
}

async function main() {
  const args = parseCliArgs(process.argv.slice(2));
  const dryRun = Boolean(args["dry-run"] || process.env.FCM_DRY_RUN === "true");
  const allowPrerelease = Boolean(args["allow-prerelease"]);

  const releaseJson = args["release-json"]
    ? JSON.parse(fs.readFileSync(args["release-json"], "utf8"))
    : null;
  const manifestJson = args["manifest-json"]
    ? JSON.parse(fs.readFileSync(args["manifest-json"], "utf8"))
    : null;

  const releaseMeta = resolveAndValidateReleasePayload({
    releaseJson,
    manifestJson,
    versionName: args["version-name"] || process.env.VERSION_NAME || "",
    versionCode: Number(args["version-code"] || process.env.VERSION_CODE || 0),
    apkUrl: args["apk-url"] || process.env.APK_URL || "",
    sha256: args["sha256"] || process.env.APK_SHA256 || "",
    fileSizeBytes: Number(args["file-size"] || process.env.APK_SIZE || 0),
    publishedAt: args["published-at"] || process.env.PUBLISHED_AT || "",
    releaseNotes: args["release-notes"] || process.env.RELEASE_NOTES || "",
    releaseTag: args["release-tag"] || process.env.RELEASE_TAG || "",
    allowPrerelease,
  });

  let serviceAccount = null;
  if (!dryRun) {
    const rawServiceAccount = args["service-account-file"]
      ? fs.readFileSync(args["service-account-file"], "utf8")
      : process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "";
    if (!rawServiceAccount.trim()) {
      console.warn(
        "FIREBASE_SERVICE_ACCOUNT_JSON secret is not configured; skipping FCM release push notification cleanly."
      );
      process.exit(0);
    }
    serviceAccount = JSON.parse(rawServiceAccount);
  }

  const result = await sendFcmReleaseNotification({
    serviceAccount,
    releaseMeta,
    topic: args["topic"] || process.env.FCM_UPDATE_TOPIC || DEFAULT_FCM_TOPIC,
    dryRun,
    dedupeMarkerFile: args["dedupe-marker-file"] || process.env.FCM_DEDUPE_MARKER_FILE || "",
    tokenEndpoint: args["token-endpoint"] || null,
    fcmEndpoint: args["fcm-endpoint"] || null,
  });

  if (result.status === "skipped_duplicate") {
    console.log(`Skipping duplicate FCM release notification for ${result.dedupeKey}.`);
  } else if (result.status === "dry_run") {
    console.log(
      `[DRY-RUN] Validated FCM release notification for v${releaseMeta.versionName} (Build ${releaseMeta.versionCode}) -> topic '${result.payload.message.topic}'.`
    );
  } else {
    console.log(
      `Sent FCM release notification for v${releaseMeta.versionName} (Build ${releaseMeta.versionCode}) to topic '${result.payload.message.topic}' (messageId=${result.messageId || "ok"}).`
    );
  }
}

const isMainModule =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isMainModule) {
  main().catch((err) => {
    console.error(`FCM release notification failed: ${err?.message || err}`);
    process.exit(1);
  });
}

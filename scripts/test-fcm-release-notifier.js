"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const ROOT_DIR = path.resolve(__dirname, "..");
const NOTIFIER_PATH = path.join(ROOT_DIR, "scripts/send-fcm-release-notification.mjs");
const NOTIFY_WORKFLOW_PATH = path.join(ROOT_DIR, ".github/workflows/notify-release-update.yml");
const RELEASE_WORKFLOW_PATH = path.join(ROOT_DIR, ".github/workflows/release.yml");

let notifierModulePromise = null;
function loadNotifier() {
  if (!notifierModulePromise) {
    notifierModulePromise = import(pathToFileURL(NOTIFIER_PATH).href);
  }
  return notifierModulePromise;
}

const VALID_SHA = "a".repeat(64);

function makeSampleGitHubRelease(overrides = {}) {
  return {
    tag_name: "v1.0.19",
    name: "Share Flow v1.0.19 (Build 20)",
    draft: false,
    prerelease: false,
    published_at: "2026-11-25T10:00:00Z",
    body: "- تحسين سرعة التحديث التلقائي\n- دعم الإشعارات الفورية عبر FCM",
    assets: [
      {
        name: "Share-Flow.apk",
        size: 18450000,
        digest: `sha256:${VALID_SHA}`,
        browser_download_url:
          "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
      },
    ],
    ...overrides,
  };
}

function makeEphemeralServiceAccount() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
  });
  return {
    publicKey,
    serviceAccount: {
      project_id: "shareflow-test-project",
      client_email: "fcm-publisher@shareflow-test-project.iam.gserviceaccount.com",
      private_key: privateKey,
    },
  };
}

function startLocalMockFcmServer(publicKeyPem, { failFirstAttempts = 0, failStatus = 500 } = {}) {
  const captured = {
    tokenRequests: 0,
    fcmRequests: 0,
    jwtVerified: false,
    lastAuthHeader: null,
    lastFcmBody: null,
  };

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      if (req.url === "/token") {
        captured.tokenRequests += 1;
        const params = new URLSearchParams(raw);
        const assertion = params.get("assertion") || "";
        const [headerB64, payloadB64, sigB64] = assertion.split(".");
        const signingInput = `${headerB64}.${payloadB64}`;
        const sigBuf = Buffer.from(sigB64.replace(/-/g, "+").replace(/_/g, "/"), "base64");
        const verified = crypto.verify("RSA-SHA256", Buffer.from(signingInput, "utf8"), publicKeyPem, sigBuf);
        captured.jwtVerified = verified;
        if (!verified) {
          res.writeHead(401, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "invalid_signature" }));
          return;
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ access_token: "ephemeral-test-access-token", token_type: "Bearer" }));
        return;
      }

      if (req.url === "/v1/projects/shareflow-test-project/messages:send") {
        captured.fcmRequests += 1;
        captured.lastAuthHeader = req.headers.authorization || null;
        if (captured.fcmRequests <= failFirstAttempts) {
          res.writeHead(failStatus, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: { code: failStatus, message: "transient failure" } }));
          return;
        }
        captured.lastFcmBody = JSON.parse(raw);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "projects/shareflow-test-project/messages/msg-12345" }));
        return;
      }

      res.writeHead(404);
      res.end();
    });
  });

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      resolve({
        baseUrl: `http://127.0.0.1:${addr.port}`,
        captured,
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}

test("resolves valid GitHub Release using native asset sha256 digest and rejects draft/prerelease/invalid releases", async () => {
  const mod = await loadNotifier();

  const meta = mod.resolveAndValidateReleasePayload({
    releaseJson: makeSampleGitHubRelease(),
  });
  assert.equal(meta.releaseTag, "v1.0.19");
  assert.equal(meta.versionName, "1.0.19");
  assert.equal(meta.versionCode, 20);
  assert.equal(meta.sha256, VALID_SHA);
  assert.equal(meta.fileSizeBytes, 18450000);
  assert.match(meta.apkUrl, /^https:\/\/github\.com\/.*Share-Flow\.apk$/);

  // Reject draft
  assert.throws(
    () => mod.resolveAndValidateReleasePayload({ releaseJson: makeSampleGitHubRelease({ draft: true }) }),
    /draft/i
  );

  // Reject prerelease by default
  assert.throws(
    () => mod.resolveAndValidateReleasePayload({ releaseJson: makeSampleGitHubRelease({ prerelease: true }) }),
    /pre-release/i
  );

  // Reject missing APK asset
  assert.throws(
    () => mod.resolveAndValidateReleasePayload({ releaseJson: makeSampleGitHubRelease({ assets: [] }) }),
    /APK/i
  );

  // Reject missing SHA-256
  assert.throws(
    () =>
      mod.resolveAndValidateReleasePayload({
        releaseJson: makeSampleGitHubRelease({
          body: "No checksum here",
          assets: [
            {
              name: "Share-Flow.apk",
              size: 1000,
              browser_download_url: "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
            },
          ],
        }),
      }),
    /SHA-256/i
  );

  // Reject cleartext HTTP APK URL
  assert.throws(
    () =>
      mod.resolveAndValidateReleasePayload({
        versionName: "1.0.19",
        versionCode: 20,
        apkUrl: "http://example.com/Share-Flow.apk",
        sha256: VALID_SHA,
      }),
    /HTTPS/i
  );
});

test("sends RS256-authenticated FCM HTTP v1 topic message, retries transient 500/429, and prevents duplicates", async () => {
  const mod = await loadNotifier();
  const { publicKey, serviceAccount } = makeEphemeralServiceAccount();
  const mockServer = await startLocalMockFcmServer(publicKey, { failFirstAttempts: 2, failStatus: 503 });
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "shareflow-fcm-test-"));
  const dedupeFile = path.join(tmpDir, "notified.json");

  try {
    const releaseMeta = mod.resolveAndValidateReleasePayload({
      releaseJson: makeSampleGitHubRelease(),
    });

    const first = await mod.sendFcmReleaseNotification({
      serviceAccount,
      releaseMeta,
      dedupeMarkerFile: dedupeFile,
      tokenEndpoint: `${mockServer.baseUrl}/token`,
      fcmEndpoint: `${mockServer.baseUrl}/v1/projects/shareflow-test-project/messages:send`,
      retryDelayMs: 10,
    });

    assert.equal(first.status, "sent");
    assert.equal(first.messageId, "projects/shareflow-test-project/messages/msg-12345");
    assert.equal(mockServer.captured.jwtVerified, true);
    assert.equal(mockServer.captured.fcmRequests, 3); // 2 transient failures + 1 success
    assert.equal(mockServer.captured.lastAuthHeader, "Bearer ephemeral-test-access-token");

    const sentMsg = mockServer.captured.lastFcmBody.message;
    assert.equal(sentMsg.topic, "share_flow_updates");
    assert.equal(sentMsg.notification.title, "تحديث جديد متوفر | Share Flow");
    assert.match(sentMsg.notification.body, /v1\.0\.19/);
    assert.equal(sentMsg.android.priority, "HIGH");
    assert.equal(sentMsg.android.notification.channel_id, "share_flow_updates_channel");
    assert.equal(sentMsg.android.notification.click_action, "OPEN_SHAREFLOW_UPDATES");
    assert.equal(sentMsg.data.type, "app_update");
    assert.equal(sentMsg.data.versionName, "1.0.19");
    assert.equal(sentMsg.data.versionCode, "20");
    assert.equal(sentMsg.data.sha256, VALID_SHA);
    assert.equal(sentMsg.data.targetRoute, "updates");

    // Re-running for the exact same release must skip without calling FCM again
    const second = await mod.sendFcmReleaseNotification({
      serviceAccount,
      releaseMeta,
      dedupeMarkerFile: dedupeFile,
      tokenEndpoint: `${mockServer.baseUrl}/token`,
      fcmEndpoint: `${mockServer.baseUrl}/v1/projects/shareflow-test-project/messages:send`,
      retryDelayMs: 10,
    });

    assert.equal(second.status, "skipped_duplicate");
    assert.equal(mockServer.captured.fcmRequests, 3);
  } finally {
    await mockServer.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("GitHub Actions FCM notification workflow enforces least privilege, release.published trigger, dedupe cache, and non-fatal push", () => {
  const notifyYml = fs.readFileSync(NOTIFY_WORKFLOW_PATH, "utf8");
  const releaseYml = fs.readFileSync(RELEASE_WORKFLOW_PATH, "utf8");

  assert.match(notifyYml, /release:\s*\n\s*types:\s*\[published\]/);
  assert.match(notifyYml, /permissions:\s*\n\s*contents:\s*read/);
  assert.match(notifyYml, /continue-on-error:\s*true/);
  assert.match(notifyYml, /secrets\.FIREBASE_SERVICE_ACCOUNT_JSON/);
  assert.match(notifyYml, /shareflow-fcm-notified-/);
  assert.doesNotMatch(notifyYml, /-----BEGIN PRIVATE KEY-----/);

  assert.match(releaseYml, /id:\s*fcm_notify/);
  assert.match(releaseYml, /send-fcm-release-notification\.mjs/);
  assert.match(releaseYml, /secrets\.FIREBASE_SERVICE_ACCOUNT_JSON/);
  assert.doesNotMatch(releaseYml, /-----BEGIN PRIVATE KEY-----/);
});

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const ROOT_DIR = path.resolve(__dirname, "..");
const WORKFLOW_PATH = path.join(ROOT_DIR, ".github/workflows/release.yml");
const HELPER_PATH = path.join(ROOT_DIR, "scripts/release_helpers.py");
const GRADLE_PATH = path.join(ROOT_DIR, "app/build.gradle.kts");
const portal = require("../docs/app.js");

function runHelper(args) {
  return execFileSync("python3", [HELPER_PATH, ...args], {
    cwd: ROOT_DIR,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "shareflow-release-test-"));
}

test("release.yml preserves name, triggers, concurrency, atomic draft->promote, dry_run, and summary", () => {
  const yml = fs.readFileSync(WORKFLOW_PATH, "utf8");

  assert.match(yml, /^name:\s*ShareFlow Production Release & Public APK Distribution$/m);
  assert.match(yml, /tags:\s*\n\s*-\s*"v\[0-9\]\+\.\[0-9\]\+\.\[0-9\]\+"/);
  assert.match(yml, /workflow_dispatch:/);
  assert.match(yml, /\bdry_run:/);
  assert.match(yml, /concurrency:\s*\n\s*group:\s*shareflow-production-release\s*\n\s*cancel-in-progress:\s*false/);
  assert.match(yml, /permissions:\s*\n\s*contents:\s*read/);

  // Pre-build version check must execute BEFORE Gradle build
  const prebuildIdx = yml.indexOf("validate-prebuild");
  const assembleIdx = yml.indexOf(":app:assembleRelease");
  assert.ok(prebuildIdx > 0, "Expected validate-prebuild step in release.yml");
  assert.ok(assembleIdx > prebuildIdx, "Pre-build version check must run before :app:assembleRelease");

  // Atomic publish guard: draft creation, error trap cleanup, asset verification, then promote
  const draftIdx = yml.indexOf("--draft");
  const trapIdx = yml.indexOf("trap cleanup_failed_draft ERR");
  const promoteIdx = yml.indexOf("--draft=false");
  const syncStepIdx = yml.indexOf("id: sync_website");
  const liveSyncIdx = yml.lastIndexOf("bash ./scripts/sync-public-docs.sh");
  assert.ok(trapIdx > 0 && draftIdx > trapIdx, "Rollback trap must be registered before creating draft release");
  assert.ok(promoteIdx > draftIdx, "Draft release must be promoted only after verification");
  assert.ok(syncStepIdx > promoteIdx && liveSyncIdx > syncStepIdx, "Live website sync must run only after public release succeeds");

  // Dry-run gating on all public mutation steps
  const dryRunGuards = yml.match(/steps\.validate_config\.outputs\.dry_run != 'true'/g) || [];
  assert.ok(dryRunGuards.length >= 3, "Publish, public probe, and website sync must all be gated by dry_run");

  // Final summary step
  assert.match(yml, /GITHUB_STEP_SUMMARY/);

  // Must use REST gh api instead of GraphQL gh repo view, sanitize GH_TOKEN, and handle HTTP 401 cleanly
  assert.doesNotMatch(yml, /\bgh repo view\b/, "Must use REST gh api repos/... instead of GraphQL gh repo view");
  assert.match(yml, /gh api "repos\/\$\{PUBLIC_REPO\}"/, "Expected REST gh api repository verification");
  assert.match(yml, /401\|Bad credentials/, "Expected explicit HTTP 401 Bad credentials diagnostic handling");
  assert.match(yml, /tr -d '\\r\\n'/, "Expected CR/LF sanitization for secrets/tokens");

  // Sibling workflows must remain intact
  for (const wf of ["blank.yml", "sync-website.yml", "generate-release-keystore.yml"]) {
    assert.ok(fs.existsSync(path.join(ROOT_DIR, ".github/workflows", wf)), `Missing sibling workflow: ${wf}`);
  }
});

test("release_helpers validate-prebuild succeeds on current app/build.gradle.kts with lower previous versionCode", () => {
  const tmp = makeTempDir();
  try {
    const prevManifest = path.join(tmp, "prev-version.json");
    const ghOutput = path.join(tmp, "github-output.txt");
    fs.writeFileSync(
      prevManifest,
      JSON.stringify({ versionCode: 12, versionName: "1.0.11" }),
      "utf8"
    );

    const gradleText = fs.readFileSync(GRADLE_PATH, "utf8");
    const verMatch = gradleText.match(/versionName\s*=\s*"([^"]+)"/);
    const codeMatch = gradleText.match(/versionCode\s*=\s*([0-9]+)/);
    assert.ok(verMatch && codeMatch);
    const currentTag = `v${verMatch[1]}`;
    const currentCode = Number(codeMatch[1]);

    // Ensure previous versionCode is strictly lower than current Gradle versionCode
    fs.writeFileSync(
      prevManifest,
      JSON.stringify({ versionCode: currentCode - 1, versionName: "1.0.0" }),
      "utf8"
    );

    const out = runHelper([
      "validate-prebuild",
      "--gradle",
      GRADLE_PATH,
      "--release-tag",
      currentTag,
      "--prev-manifest",
      prevManifest,
      "--github-output",
      ghOutput,
    ]);

    assert.match(out, /Pre-build version check passed/);
    const outputText = fs.readFileSync(ghOutput, "utf8");
    assert.match(outputText, new RegExp(`gradle_version_code=${currentCode}`));
    assert.match(outputText, new RegExp(`gradle_version_name=${verMatch[1].replace(/\./g, "\\.")}`));
    assert.match(outputText, new RegExp(`previous_version_code=${currentCode - 1}`));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("release_helpers validate-prebuild blocks equal/downgraded versionCode, tag mismatch, and applicationId tamper", () => {
  const tmp = makeTempDir();
  try {
    const gradleText = fs.readFileSync(GRADLE_PATH, "utf8");
    const verMatch = gradleText.match(/versionName\s*=\s*"([^"]+)"/);
    const codeMatch = gradleText.match(/versionCode\s*=\s*([0-9]+)/);
    const currentTag = `v${verMatch[1]}`;
    const currentCode = Number(codeMatch[1]);

    // 1. Equal versionCode must fail
    const equalPrev = path.join(tmp, "equal.json");
    fs.writeFileSync(equalPrev, JSON.stringify({ versionCode: currentCode, versionName: "1.0.0" }));
    assert.throws(() =>
      runHelper([
        "validate-prebuild",
        "--gradle",
        GRADLE_PATH,
        "--release-tag",
        currentTag,
        "--prev-manifest",
        equalPrev,
      ])
    );

    // 2. Downgraded versionCode must fail
    const higherPrev = path.join(tmp, "higher.json");
    fs.writeFileSync(higherPrev, JSON.stringify({ versionCode: currentCode + 1, versionName: "1.0.99" }));
    assert.throws(() =>
      runHelper([
        "validate-prebuild",
        "--gradle",
        GRADLE_PATH,
        "--release-tag",
        currentTag,
        "--prev-manifest",
        higherPrev,
      ])
    );

    // 3. Tag / versionName mismatch must fail
    assert.throws(() =>
      runHelper([
        "validate-prebuild",
        "--gradle",
        GRADLE_PATH,
        "--release-tag",
        "v9.9.9",
      ])
    );

    // 4. Invalid tag format (missing 'v') must fail
    assert.throws(() =>
      runHelper([
        "validate-prebuild",
        "--gradle",
        GRADLE_PATH,
        "--release-tag",
        verMatch[1],
      ])
    );

    // 5. Tampered applicationId must fail
    const badGradle = path.join(tmp, "bad.build.gradle.kts");
    fs.writeFileSync(
      badGradle,
      gradleText.replace("com.aistudio.shareflow.kxrvnm", "com.evil.otherapp")
    );
    assert.throws(() =>
      runHelper([
        "validate-prebuild",
        "--gradle",
        badGradle,
        "--release-tag",
        currentTag,
      ])
    );
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("release_helpers verify-postbuild enforces signing certificate continuity and monotonic versionCode", () => {
  const tmp = makeTempDir();
  try {
    const certA = "a".repeat(64);
    const certB = "b".repeat(64);
    const prevManifest = path.join(tmp, "prev.json");
    fs.writeFileSync(
      prevManifest,
      JSON.stringify({ versionCode: 12, signingCertSha256: certA }),
      "utf8"
    );

    // Valid upgrade with identical certificate
    const okOut = runHelper([
      "verify-postbuild",
      "--version-code",
      "13",
      "--cert-sha256",
      certA,
      "--prev-manifest",
      prevManifest,
    ]);
    assert.match(okOut, /Verified monotonic upgrade continuity/);

    // Changed certificate must fail
    assert.throws(() =>
      runHelper([
        "verify-postbuild",
        "--version-code",
        "13",
        "--cert-sha256",
        certB,
        "--prev-manifest",
        prevManifest,
      ])
    );

    // Non-increasing versionCode must fail
    assert.throws(() =>
      runHelper([
        "verify-postbuild",
        "--version-code",
        "12",
        "--cert-sha256",
        certA,
        "--prev-manifest",
        prevManifest,
      ])
    );
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("release_helpers build-manifest generates valid version.json accepted by docs/app.js", () => {
  const tmp = makeTempDir();
  try {
    const manifestOut = path.join(tmp, "version.json");
    const apkSha = "1".repeat(64);
    const certSha = "2".repeat(64);
    const latestUrl = "https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk";
    const directUrl = "https://github.com/ow7ab/Share-Flow-Releases/releases/download/v1.0.12/Share-Flow.apk";

    runHelper([
      "build-manifest",
      "--version-code",
      "13",
      "--version-name",
      "1.0.12",
      "--apk-url",
      latestUrl,
      "--apk-download-url",
      directUrl,
      "--sha256",
      apkSha,
      "--file-size",
      "21500000",
      "--min-sdk",
      "24",
      "--published-at",
      "2026-11-23T05:00:00Z",
      "--release-tag",
      "v1.0.12",
      "--cert-sha256",
      certSha,
      "--notes-ar",
      "- أتمتة كاملة للإصدار\n- تحسينات أمان التحديث",
      "--notes-en",
      "- Full release automation\n- Update security hardening",
      "--output",
      manifestOut,
    ]);

    const manifest = JSON.parse(fs.readFileSync(manifestOut, "utf8"));
    assert.equal(manifest.versionCode, 13);
    assert.equal(manifest.versionName, "1.0.12");
    assert.equal(manifest.apkUrl, latestUrl);
    assert.equal(manifest.apkDownloadUrl, directUrl);
    assert.equal(manifest.sha256, apkSha);
    assert.equal(manifest.signingCertSha256, certSha);
    assert.equal(manifest.mandatory, false);
    assert.deepEqual(manifest.releaseNotes, ["أتمتة كاملة للإصدار", "تحسينات أمان التحديث"]);

    // Cross-validate directly with docs/app.js website parser
    const parsed = portal.normalizeCustomManifest(manifest);
    assert.equal(parsed.status, "ready");
    assert.equal(parsed.data.buildNumber, 13);
    assert.equal(parsed.data.sha256, apkSha);
    assert.equal(parsed.data.apkUrl, latestUrl);

    // Reject insecure HTTP or non-APK URL
    assert.throws(() =>
      runHelper([
        "build-manifest",
        "--version-code",
        "13",
        "--version-name",
        "1.0.12",
        "--apk-url",
        "http://example.com/Share-Flow.zip",
        "--apk-download-url",
        directUrl,
        "--sha256",
        apkSha,
        "--file-size",
        "21500000",
        "--published-at",
        "2026-11-23T05:00:00Z",
        "--release-tag",
        "v1.0.12",
        "--cert-sha256",
        certSha,
        "--output",
        path.join(tmp, "bad.json"),
      ])
    );
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("generate-release-keystore.yml DN validator accepts 'Share Flow' and rejects malformed/injected inputs", () => {
  const ksWorkflowPath = path.join(ROOT_DIR, ".github/workflows/generate-release-keystore.yml");
  const yml = fs.readFileSync(ksWorkflowPath, "utf8");

  assert.doesNotMatch(yml, /grep -Eq '\[,="\\\\`\$;\\r\\n\]'/, "Must not use broken POSIX grep \\r\\n inside [...]");
  const pyMatch = yml.match(/python3 - <<'PYEOF'\n([\s\S]*?)\n\s*PYEOF/);
  assert.ok(pyMatch, "Expected Python DN validator block in generate-release-keystore.yml");

  const rawLines = pyMatch[1].split("\n");
  const indents = rawLines.filter((l) => l.trim().length > 0).map((l) => l.match(/^\s*/)[0].length);
  const minIndent = Math.min(...indents);
  const pyCode = rawLines.map((l) => (l.length >= minIndent ? l.slice(minIndent) : "")).join("\n");

  // Default values with letters 'r' and 'n' must succeed
  execFileSync("python3", ["-c", pyCode], {
    env: { ...process.env, DNAME_CN: "Share Flow", DNAME_O: "Share Flow Engineering", DNAME_C: "SD" },
    stdio: "pipe",
  });

  // Disallowed characters (comma, equals, newline, command substitution) must fail
  for (const badEnv of [
    { DNAME_CN: "Share,Flow", DNAME_O: "Share Flow", DNAME_C: "SD" },
    { DNAME_CN: "Share=Flow", DNAME_O: "Share Flow", DNAME_C: "SD" },
    { DNAME_CN: "Share\nFlow", DNAME_O: "Share Flow", DNAME_C: "SD" },
    { DNAME_CN: "$(whoami)", DNAME_O: "Share Flow", DNAME_C: "SD" },
    { DNAME_CN: "Share Flow", DNAME_O: "Share Flow", DNAME_C: "SUD" },
  ]) {
    assert.throws(() =>
      execFileSync("python3", ["-c", pyCode], {
        env: { ...process.env, ...badEnv },
        stdio: "pipe",
      })
    );
  }
});

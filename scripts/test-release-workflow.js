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

test("release.yml preserves name, unified triggers, concurrency, atomic draft->promote, dry_run, and summary", () => {
  const yml = fs.readFileSync(WORKFLOW_PATH, "utf8");

  assert.match(yml, /^name:\s*ShareFlow Production Release & Public APK Distribution$/m);
  assert.match(yml, /branches:\s*\n\s*-\s*main/);
  assert.match(yml, /tags:\s*\n\s*-\s*"v\[0-9\]\+\.\[0-9\]\+\.\[0-9\]\+"/);
  assert.match(yml, /workflow_dispatch:/);
  assert.match(yml, /\bdry_run:/);
  assert.match(yml, /concurrency:\s*\n\s*group:\s*shareflow-production-release\s*\n\s*cancel-in-progress:\s*false/);
  assert.match(yml, /permissions:\s*\n\s*contents:\s*write/);

  // Unified single-repo architecture: uses built-in github.token, never legacy PAT or Share-Flow-Releases
  assert.doesNotMatch(yml, /PUBLIC_RELEASES_TOKEN/, "Unified release.yml must not require legacy PUBLIC_RELEASES_TOKEN");
  assert.doesNotMatch(yml, /Share-Flow-Releases/, "Unified release.yml must not target legacy Share-Flow-Releases");

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
  const liveSyncIdx = yml.lastIndexOf("bash ./scripts/sync-public-docs.sh --local-update");
  assert.ok(trapIdx > 0 && draftIdx > trapIdx, "Rollback trap must be registered before creating draft release");
  assert.ok(promoteIdx > draftIdx, "Draft release must be promoted only after verification");
  assert.ok(syncStepIdx > promoteIdx && liveSyncIdx > syncStepIdx, "Local docs/version.json update must run only after release succeeds");

  // Dry-run gating on all public mutation steps
  const dryRunGuards = yml.match(/steps\.validate_config\.outputs\.dry_run != 'true'/g) || [];
  assert.ok(dryRunGuards.length >= 3, "Publish, public probe, and website manifest update must all be gated by dry_run");

  // Final summary step
  assert.match(yml, /GITHUB_STEP_SUMMARY/);

  // Must use REST gh api instead of GraphQL gh repo view, sanitize GH_TOKEN & KEYSTORE_SETUP_TOKEN, and fail fast on HTTP 401
  assert.doesNotMatch(yml, /\bgh repo view\b/, "Must use REST gh api repos/... instead of GraphQL gh repo view");
  assert.match(yml, /gh api "repos\/\$\{PUBLIC_REPO\}"/, "Expected REST gh api repository verification");
  assert.match(yml, /gh api "repos\/\$\{SOURCE_REPO\}\/actions\/secrets\?per_page=100"/, "Expected fail-fast KEYSTORE_SETUP_TOKEN pre-check before JDK setup");
  assert.match(yml, /401\|Bad credentials/, "Expected explicit HTTP 401 Bad credentials diagnostic handling");
  assert.match(yml, /tr -d '\\r\\n'/, "Expected CR/LF sanitization for secrets/tokens");
  assert.match(yml, /\(\?:Bearer\|token\)/, "Expected Bearer/token prefix sanitization for KEYSTORE_SETUP_TOKEN");
  assert.match(yml, /\\u200b-\\u200f/, "Expected invisible Unicode RTL/zero-width sanitization for mobile copy/paste");
  assert.match(yml, /TOKEN_DIAG/, "Expected non-sensitive token format/length diagnostic on failure");

  // Sibling workflows must remain intact
  for (const wf of [
    "blank.yml",
    "sync-website.yml",
    "generate-release-keystore.yml",
    "notify-release-update.yml",
    "migrate-releases-from-legacy.yml",
    "deploy-plesk.yml",
  ]) {
    assert.ok(fs.existsSync(path.join(ROOT_DIR, ".github/workflows", wf)), `Missing sibling workflow: ${wf}`);
  }

  const ksYml = fs.readFileSync(path.join(ROOT_DIR, ".github/workflows/generate-release-keystore.yml"), "utf8");
  assert.match(ksYml, /\(\?:Bearer\|token\)/, "Expected KEYSTORE_SETUP_TOKEN sanitization in generate-release-keystore.yml");
  assert.match(ksYml, /\\u200b-\\u200f/, "Expected Unicode bidi/zero-width sanitization in generate-release-keystore.yml");
  assert.match(ksYml, /401\|Bad credentials/, "Expected HTTP 401 diagnostic in generate-release-keystore.yml");
});

test("deploy-plesk.yml and FTPS scripts enforce docs-only staging, --env-password, and Let's Encrypt exclusions", () => {
  const pleskWorkflowPath = path.join(ROOT_DIR, ".github/workflows/deploy-plesk.yml");
  const stageScriptPath = path.join(ROOT_DIR, "scripts/stage-public-docs.sh");
  const ftpsScriptPath = path.join(ROOT_DIR, "scripts/deploy-plesk-ftps.sh");
  const syncScriptPath = path.join(ROOT_DIR, "scripts/sync-public-docs.sh");

  const wf = fs.readFileSync(pleskWorkflowPath, "utf8");
  assert.match(wf, /permissions:\s*\n\s*contents:\s*read/);
  assert.match(wf, /concurrency:\s*\n\s*group:\s*shareflow-plesk-website-deploy\s*\n\s*cancel-in-progress:\s*false/);
  assert.match(wf, /-\s*"docs\/\*\*"/);
  assert.match(wf, /PLESK_FTP_PASSWORD:\s*\$\{\{\s*secrets\.PLESK_FTP_PASSWORD\s*\}\}/);
  assert.match(wf, /196\.1\.255\.37/);
  assert.match(wf, /\/httpdocs/);
  assert.match(wf, /dry_run != true/);

  const stageScript = fs.readFileSync(stageScriptPath, "utf8");
  assert.match(stageScript, /ALLOWED_REGEX=/);
  assert.match(stageScript, /ow7ab\/Share-Flow/);
  assert.match(stageScript, /PLESK_FTP_PASSWORD/);

  const syncScript = fs.readFileSync(syncScriptPath, "utf8");
  assert.match(syncScript, /stage-public-docs\.sh/);

  const ftpsScript = fs.readFileSync(ftpsScriptPath, "utf8");
  assert.match(ftpsScript, /stage-public-docs\.sh/);
  assert.match(ftpsScript, /export LFTP_PASSWORD=/);
  assert.match(ftpsScript, /--env-password/);
  assert.match(ftpsScript, /\.well-known/);
  assert.match(ftpsScript, /--skip-if-unconfigured/);
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
      gradleText.replace("com.shareflow", "com.evil.otherapp")
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

    // Valid upgrade with identical certificate and matching Gradle versionCode/versionName
    const okOut = runHelper([
      "verify-postbuild",
      "--version-code",
      "13",
      "--expected-version-code",
      "13",
      "--version-name",
      "1.0.12",
      "--expected-version-name",
      "1.0.12",
      "--cert-sha256",
      certA,
      "--prev-manifest",
      prevManifest,
    ]);
    assert.match(okOut, /Verified monotonic upgrade continuity/);

    // Built APK versionCode disagreeing with Gradle versionCode must fail
    assert.throws(() =>
      runHelper([
        "verify-postbuild",
        "--version-code",
        "13",
        "--expected-version-code",
        "14",
        "--version-name",
        "1.0.12",
        "--expected-version-name",
        "1.0.12",
        "--cert-sha256",
        certA,
        "--prev-manifest",
        prevManifest,
      ])
    );

    // Built APK versionName disagreeing with Gradle versionName must fail
    assert.throws(() =>
      runHelper([
        "verify-postbuild",
        "--version-code",
        "13",
        "--expected-version-code",
        "13",
        "--version-name",
        "1.0.11",
        "--expected-version-name",
        "1.0.12",
        "--cert-sha256",
        certA,
        "--prev-manifest",
        prevManifest,
      ])
    );

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
    const latestUrl = "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk";
    const directUrl = "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.12/Share-Flow.apk";

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
    assert.equal(manifest.downloadUrl, directUrl);
    assert.equal(manifest.apkUrl, latestUrl);
    assert.equal(manifest.apkDownloadUrl, directUrl);
    assert.equal(manifest.sha256, apkSha);
    assert.equal(manifest.signingCertSha256, certSha);
    assert.equal(manifest.isMandatory, false);
    assert.equal(manifest.mandatory, false);
    assert.deepEqual(manifest.releaseNotes, ["أتمتة كاملة للإصدار", "تحسينات أمان التحديث"]);

    // Cross-validate directly with docs/app.js website parser
    const parsed = portal.normalizeCustomManifest(manifest);
    assert.equal(parsed.status, "ready");
    assert.equal(parsed.data.buildNumber, 13);
    assert.equal(parsed.data.isMandatory, false);
    assert.equal(parsed.data.sha256, apkSha);
    assert.equal(parsed.data.apkUrl, latestUrl);

    // Verify docs/app.js also handles canonical minimal unified schema (downloadUrl + isMandatory + V1.1.2)
    const minimalParsed = portal.normalizeCustomManifest({
      versionCode: 25,
      versionName: "V1.1.2",
      downloadUrl: "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk",
      releaseNotes: "Unified update schema",
      isMandatory: true,
      sha256: apkSha,
    });
    assert.equal(minimalParsed.status, "ready");
    assert.equal(minimalParsed.data.versionLabel, "v1.1.2 (Build 25)");
    assert.equal(minimalParsed.data.buildNumber, 25);
    assert.equal(minimalParsed.data.isMandatory, true);

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

test("migrate_releases.py plans idempotent chronological migration, SHA-256 conflict verification, and orphan tag preservation", () => {
  const pyCheck = `
import json, sys
sys.path.insert(0, "scripts")
import migrate_releases as m

sha_13 = "f227a700740aa0ec420452e251866552bbde7d9c5786d25e39353eae061bb8bc"
sha_21 = "b20170132b6de6b4efef62ea5587dca213b86760c4a581d3975633960dcf23e9"

source_releases = [
    {
        "tag_name": "V1.0.21",
        "name": "Share Flow v1.0.21",
        "draft": False,
        "prerelease": False,
        "published_at": "2026-11-26T00:00:00Z",
        "body": "Latest stable",
        "assets": [{"name": "ShareFlow.apk", "size": 29440817, "digest": f"sha256:{sha_21}"}],
    },
    {
        "tag_name": "V1.0.13",
        "name": "Share Flow v1.0.13",
        "draft": False,
        "prerelease": True,
        "published_at": "2026-10-01T00:00:00Z",
        "body": "Existing release",
        "assets": [{"name": "ShareFlow.1.0.13.apk", "size": 51785405, "digest": f"sha256:{sha_13}"}],
    },
    {
        "tag_name": "V1.0.19",
        "name": "Share Flow v1.0.19",
        "draft": False,
        "prerelease": False,
        "published_at": "2026-11-10T00:00:00Z",
        "body": "Previous stable",
        "assets": [{"name": "ShareFlow.V1.0.19.apk", "size": 29424433, "digest": "sha256:" + "a" * 64}],
    },
]
target_releases = [
    {
        "tag_name": "V1.0.13",
        "name": "Share Flow v1.0.13",
        "draft": False,
        "prerelease": True,
        "assets": [{"name": "ShareFlow.1.0.13.apk", "size": 51785405, "digest": f"sha256:{sha_13}"}],
    },
]
source_tags = [{"name": "V1.0.21"}, {"name": "V1.0.20"}, {"name": "V1.0.19"}, {"name": "V1.0.13"}]
target_tags = [{"name": "V1.0.13"}]

plan = m.plan_migration(source_releases, target_releases, source_tags, target_tags)
assert plan["latest_stable_tag"] == "V1.0.21"
assert [a["tag"] for a in plan["release_actions"]] == ["V1.0.13", "V1.0.19", "V1.0.21"]
assert plan["release_actions"][0]["action"] == "verify_existing"
assert plan["release_actions"][0]["assets_verified_existing"] == ["ShareFlow.1.0.13.apk"]
assert plan["release_actions"][1]["action"] == "create"
assert plan["release_actions"][1]["make_latest"] is False
assert plan["release_actions"][2]["action"] == "create"
assert plan["release_actions"][2]["make_latest"] is True
assert plan["orphan_tags_to_create"] == ["V1.0.20"]

body1 = m.build_preserved_body(source_releases[0], "ow7ab/Share-Flow-Releases")
body2 = m.build_preserved_body({"tag_name": "V1.0.21", "body": body1, "published_at": "2026-11-26T00:00:00Z"}, "ow7ab/Share-Flow-Releases")
assert body1 == body2, "build_preserved_body must be idempotent"
print("migrate_releases_unit_tests_ok")
`;
  const out = execFileSync("python3", ["-c", pyCheck], { cwd: ROOT_DIR, encoding: "utf8" });
  assert.match(out, /migrate_releases_unit_tests_ok/);
});


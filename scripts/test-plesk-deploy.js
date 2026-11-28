"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT_DIR = path.resolve(__dirname, "..");

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
  assert.match(wf, /dry_run != 'true'/);

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

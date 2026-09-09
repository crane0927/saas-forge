import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { chromium } from 'playwright';
import { verifyBrowserSessions } from './browser-session-security.mjs';
import { verifyApiSecurity } from './browser-api-security.mjs';

test('development four-domain browser sessions remain isolated', async (t) => {
  assert.ok(
    process.env.SF_SESSION_EMAIL_FILE && process.env.SF_SESSION_PASSWORD_FILE,
    'set SF_SESSION_EMAIL_FILE and SF_SESSION_PASSWORD_FILE to restricted current-credential files',
  );
  const email = (await readFile(process.env.SF_SESSION_EMAIL_FILE, 'utf8')).trim();
  const password = (await readFile(process.env.SF_SESSION_PASSWORD_FILE, 'utf8')).trim();
  const directory =
    process.env.SF_SESSION_EVIDENCE_DIRECTORY ??
    (await mkdtemp(path.join(tmpdir(), 'sf-session-evidence-')));
  const browser = await chromium.launch();
  t.after(() => browser.close());
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  console.info(`EVIDENCE: ${directory}`);
  await verifyBrowserSessions({
    context,
    email,
    password,
    directory,
    verifyAuthenticated: verifyApiSecurity,
    mode: 'development',
  });
});

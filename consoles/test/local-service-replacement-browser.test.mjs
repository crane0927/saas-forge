import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('keeps UUID generation inside the serialized page callback', async () => {
  const browserScript = await readFile(
    new URL('../scripts/verify-local-service-replacement-browser.mjs', import.meta.url),
    'utf8',
  );
  const callbackStart = browserScript.indexOf('async ({ accessToken, target }) => {');
  const callbackEnd = browserScript.indexOf(
    '    },\n    { accessToken: loginBody.accessToken, target },',
    callbackStart,
  );
  const uuidGenerator = browserScript.indexOf('function createUuidV7', callbackStart);

  assert.ok(callbackStart >= 0);
  assert.ok(callbackEnd > callbackStart);
  assert.ok(uuidGenerator > callbackStart && uuidGenerator < callbackEnd);
});

test('uses the real login form and records visible, console, and network evidence', async () => {
  const browserScript = await readFile(
    new URL('../scripts/verify-local-service-replacement-browser.mjs', import.meta.url),
    'utf8',
  );

  assert.match(browserScript, /#authentication-email/u);
  assert.match(browserScript, /#authentication-password/u);
  assert.match(browserScript, /getByRole\(['"]button['"], \{ name: ['"]登录['"] \}\)/u);
  assert.match(browserScript, /getByRole\(['"]heading['"], \{ name: ['"]Platform 总览['"] \}\)/u);
  assert.match(browserScript, /requestfailed/u);
  assert.match(browserScript, /response\.status\(\) >= 500/u);
});

test('keeps HTTP localhost outside the credentialed browser Origin boundary', async () => {
  const browserScript = await readFile(
    new URL('../scripts/verify-local-development-security-browser.mjs', import.meta.url),
    'utf8',
  );

  assert.match(browserScript, /http:\/\/localhost:/u);
  assert.match(browserScript, /credentials: ['"]include['"]/u);
  assert.match(browserScript, /access-control-allow-origin/u);
  assert.match(browserScript, /access-control-allow-credentials/u);
  assert.doesNotMatch(browserScript, /ignoreHTTPSErrors/u);
});

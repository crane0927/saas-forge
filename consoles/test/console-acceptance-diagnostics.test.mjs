import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

test('reports failing acceptance source locations without exposing TAP diagnostic values', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'sf-acceptance-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const log = join(directory, 'product.log');
  await writeFile(
    log,
    `TAP version 13
not ok 2 - PRIVATE_TEST_TITLE
  ---
  location: '/runner/consoles/integration-test/console-authentication.test.mjs:65:1'
  failureType: 'testCodeFailure'
  error: |-
    initial-password-change status=401 cookieStored=true cookieObserved=false requestMatches=true problem=PASSWORD_CHANGE_SESSION_INVALID
    auth-cookie operation=login status=200 action=set attributes=true
    auth-cookie operation=refresh status=401 action=clear attributes=true
    auth-cookie operation=PRIVATE_OPERATION status=200 action=set attributes=true
    PRIVATE_ERROR_BODY
  code: 'ERR_ASSERTION'
  actual: |-
    PRIVATE_ACCESS_TOKEN
    not ok 9999 - PRIVATE_RESPONSE_TITLE
    initial-password-change status=401 cookieStored=false cookieObserved=false requestMatches=false problem=OTHER
    auth-cookie operation=login status=200 action=none attributes=false
    code: 'ERR_ASSERTION'
    at /runner/consoles/integration-test/console-authentication.test.mjs:9999:8
  expected: 'PRIVATE_PASSWORD'
  stack: |-
    PRIVATE_STACK_VALUE
    TestContext.<anonymous> (file:///runner/consoles/integration-test/console-client-acceptance.mjs:81:8)
  ...
# tests 16
# pass 15
# fail 1
`,
  );
  const script = fileURLToPath(
    new URL('../scripts/summarize-authentication-failure.mjs', import.meta.url),
  );
  const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
  assert.equal(stderr, '');
  assert.equal(
    stdout,
    [
      'FAIL: test 2',
      'AT: consoles/integration-test/console-authentication.test.mjs:65:1',
      'DIAG: initial-password-change status=401 cookieStored=true cookieObserved=false requestMatches=true problem=PASSWORD_CHANGE_SESSION_INVALID',
      'DIAG: auth-cookie operation=login status=200 action=set attributes=true',
      'DIAG: auth-cookie operation=refresh status=401 action=clear attributes=true',
      'CODE: ERR_ASSERTION',
      'AT: consoles/integration-test/console-client-acceptance.mjs:81:8',
      '# tests 16',
      '# pass 15',
      '# fail 1',
      '',
    ].join('\n'),
  );
});

test('reports compatibility failures without exposing test titles or assertion payloads', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'sf-compatibility-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const log = join(directory, 'compatibility.log');
  await writeFile(
    log,
    [
      '\u001b[31m✖ PRIVATE_TEST_TITLE (30ms)\u001b[39m',
      '  AssertionError: PRIVATE_ERROR_BODY',
      '  actual: PRIVATE_ACCESS_TOKEN',
      '  expected: PRIVATE_PASSWORD',
      '  at TestContext.<anonymous> (/runner/consoles/integration-test/session-tabs.test.mjs:137:10)',
      ' ❯ browser-test/design-system-consumers.browser.test.tsx:52:9',
      ' ❯ browser-test/showcase.browser.test.tsx:28:3',
      '  ERR_MODULE_NOT_FOUND: PRIVATE_IMPORT_TARGET',
      '  ERR_PNPM_VERIFY_DEPS_BEFORE_RUN PRIVATE_CONFIGURATION',
      'PRIVATE_STACK_VALUE',
    ].join('\n'),
  );
  const script = fileURLToPath(
    new URL('../scripts/summarize-authentication-failure.mjs', import.meta.url),
  );
  const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
  assert.equal(stderr, '');
  assert.equal(
    stdout,
    [
      'FAIL: compatibility test',
      'CODE: AssertionError',
      'AT: consoles/integration-test/session-tabs.test.mjs:137:10',
      'AT: consoles/browser-test/design-system-consumers.browser.test.tsx:52:9',
      'AT: consoles/shared/design-system/browser-test/showcase.browser.test.tsx:28:3',
      'CODE: ERR_MODULE_NOT_FOUND',
      'CODE: ERR_PNPM_VERIFY_DEPS_BEFORE_RUN',
      '',
    ].join('\n'),
  );
});

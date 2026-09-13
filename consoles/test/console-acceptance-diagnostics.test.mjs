import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

for (const target of ['local', 'ci']) {
  test(`${target} acceptance report records Chrome progress and failures without retired channels`, async (t) => {
    const directory = await mkdtemp(join(tmpdir(), 'sf-chrome-report-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const script = fileURLToPath(
      new URL('../scripts/record-authentication-acceptance.mjs', import.meta.url),
    );
    const env = {
      ...process.env,
      SF_BRAND_EVIDENCE_DIRECTORY: directory,
      SF_ACCEPTANCE_TARGET: target,
      SF_ACCEPTANCE_SCOPE: '--product',
      SF_PRODUCT_CHANNEL: '',
    };
    const record = (stage, status) =>
      promisify(execFile)(process.execPath, [script, stage, status], { env });
    const report = async () =>
      JSON.parse(await readFile(join(directory, 'acceptance-run.json'), 'utf8'));
    await record('preflight', 'running');
    assert.deepEqual((await report()).channels, [{ browser: 'chrome', status: 'not-run' }]);
    await record('product-chrome', 'running');
    assert.deepEqual((await report()).channels, [{ browser: 'chrome', status: 'running' }]);
    await record('product-chrome', 'failed');
    await record('complete', 'failed');
    const result = await report();
    assert.equal(result.status, 'failed');
    assert.equal(result.scope, '--product');
    assert.equal(result.target, target);
    assert.deepEqual(result.channels, [{ browser: 'chrome', status: 'failed' }]);
    assert.ok(
      result.stages.some(({ name, status }) => name === 'product-chrome' && status === 'failed'),
    );
  });
}

test('reports only controlled TLS hosts and fixed network observations', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'sf-tls-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const log = join(directory, 'tls.log');
  await writeFile(
    log,
    JSON.stringify({
      'platform.saasforge.example.com': 200,
      'console.saasforge.example.com': 'ERR_CONNECTION_RESET',
      'api.saasforge.example.com': 'PRIVATE_PASSWORD',
      'remote.saasforge.example.com': 404,
      PRIVATE_HOST: 'PRIVATE_BODY',
    }),
  );
  const script = fileURLToPath(
    new URL('../scripts/summarize-authentication-failure.mjs', import.meta.url),
  );
  const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
  assert.equal(stderr, '');
  assert.equal(
    stdout,
    'TLS: host=platform.saasforge.example.com result=200\nTLS: host=console.saasforge.example.com result=ERR_CONNECTION_RESET\nTLS: host=api.saasforge.example.com result=UNAVAILABLE\nTLS: host=remote.saasforge.example.com result=404\n',
  );
});

test('reports Maven failure module and fixed error codes without raw diagnostics', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'sf-maven-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const log = join(directory, 'maven.log');
  await writeFile(
    log,
    [
      '[INFO] saas-forge-openapi-contracts ................ FAILURE [ 12.345 s]',
      '[INFO] PRIVATE_MODULE ................ FAILURE [ 1.000 s]',
      '[ERROR] PRIVATE_MESSAGE MojoFailureException PRIVATE_PASSWORD',
      'not ok 18 - PRIVATE_TEST_TITLE',
      '[ERROR] Tests run: 20, Failures: 1, Errors: 0, Skipped: 0, PRIVATE_BODY',
    ].join('\n'),
  );
  const script = fileURLToPath(
    new URL('../scripts/summarize-authentication-failure.mjs', import.meta.url),
  );
  const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
  assert.equal(stderr, '');
  assert.equal(
    stdout,
    'MAVEN: failed module=saas-forge-openapi-contracts\nCODE: MojoFailureException\nFAIL: test 18\nMAVEN: tests=20 failures=1 errors=0 skipped=0\n',
  );
});

test('reports Compose status without exposing commands, unknown values or parse errors', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'sf-compose-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const log = join(directory, 'compose.json');
  const script = fileURLToPath(new URL('../scripts/summarize-compose-status.mjs', import.meta.url));
  const entries = [
    { Service: 'nacos-init', State: 'exited', Health: '', ExitCode: 1, Command: 'PRIVATE_SECRET' },
    { Service: 'gateway', State: 'running', Health: 'unhealthy', ExitCode: 0 },
    { Service: 'PRIVATE_SERVICE', State: 'running', Health: 'healthy', ExitCode: 0 },
    {
      Service: 'iam-service',
      State: 'PRIVATE_STATE',
      Health: 'PRIVATE_HEALTH',
      ExitCode: 'PRIVATE',
    },
    null,
  ];
  for (const source of [
    JSON.stringify(entries),
    entries.map((entry) => JSON.stringify(entry)).join('\n'),
  ]) {
    await writeFile(log, source);
    const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
    assert.equal(stderr, '');
    assert.equal(
      stdout,
      [
        'COMPOSE: service=nacos-init state=exited health=none exit=1',
        'COMPOSE: service=gateway state=running health=unhealthy exit=0',
        'COMPOSE: service=iam-service state=unknown health=none exit=unknown',
        '',
      ].join('\n'),
    );
  }
  await writeFile(log, 'PRIVATE_PARSE_ERROR');
  const { stdout, stderr } = await promisify(execFile)(process.execPath, [script, log]);
  assert.equal(stdout, 'COMPOSE: status unavailable\n');
  assert.equal(stderr, '');
});

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
    auth-cookie-inventory platform=2 tenant=1 other=0 partitioned=1
    auth-cookie-inventory platform=PRIVATE_COOKIE tenant=1 other=0 partitioned=1
    brand-remote inherited=true providers=1 images=0 faviconUnchanged=true context=0 imageRequests=0 fetchRequests=0 otherRequests=1
    brand-remote inherited=PRIVATE_PROFILE providers=1 images=0 faviconUnchanged=true context=0 imageRequests=0 fetchRequests=0 otherRequests=1
    auth-cookie operation=PRIVATE_OPERATION status=200 action=set attributes=true
    PRIVATE_ERROR_BODY
  code: 'ERR_ASSERTION'
  actual: |-
    PRIVATE_ACCESS_TOKEN
    not ok 9999 - PRIVATE_RESPONSE_TITLE
    initial-password-change status=401 cookieStored=false cookieObserved=false requestMatches=false problem=OTHER
    auth-cookie operation=login status=200 action=none attributes=false
    auth-cookie-inventory platform=9 tenant=9 other=9 partitioned=9
    code: 'ERR_ASSERTION'
    at /runner/consoles/integration-test/console-authentication.test.mjs:9999:8
  expected: 'PRIVATE_PASSWORD'
  stack: |-
    PRIVATE_STACK_VALUE
    TestContext.<anonymous> (file:///runner/consoles/integration-test/console-client-acceptance.mjs:81:8)
    verifyBrandRemoteInheritance (file:///runner/consoles/integration-test/brand-remote-acceptance.mjs:55:8)
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
      'DIAG: auth-cookie-inventory platform=2 tenant=1 other=0 partitioned=1',
      'DIAG: brand-remote inherited=true providers=1 images=0 faviconUnchanged=true context=0 imageRequests=0 fetchRequests=0 otherRequests=1',
      'CODE: ERR_ASSERTION',
      'AT: consoles/integration-test/console-client-acceptance.mjs:81:8',
      'AT: consoles/integration-test/brand-remote-acceptance.mjs:55:8',
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

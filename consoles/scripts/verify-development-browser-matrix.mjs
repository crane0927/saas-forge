import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { chromium, webkit } from 'playwright';

const directory =
  process.env.SF_BRAND_EVIDENCE_DIRECTORY ??
  (await mkdtemp(path.join(tmpdir(), 'sf-development-evidence-')));
await mkdir(directory, { recursive: true, mode: 0o700 });
console.info(`EVIDENCE: ${directory}`);
const results = [];
const browsers = [
  ['chromium', chromium],
  ['webkit', webkit],
  ['chrome', chromium, 'chrome'],
];
const root = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';
if (root !== 'saasforge.test')
  throw new Error('development requires the controlled saasforge.test topology');
let credentialsReady = true;
for (const name of ['SF_SESSION_EMAIL_FILE', 'SF_SESSION_PASSWORD_FILE']) {
  if (!process.env[name] || !(await readFile(process.env[name], 'utf8').catch(() => '')).trim()) {
    console.error(`BLOCKED: ${name} requires a readable, nonempty restricted credential file`);
    credentialsReady = false;
  }
}
for (const [name, engine, channel] of browsers) {
  const result = { browser: name, mode: 'development', status: 'blocked', stages: [] };
  results.push(result);
  let browser;
  let hostUnderTest = 'browser-launch';
  try {
    browser = await engine.launch({ channel });
    result.version = browser.version();
    const page = await browser.newPage({ ignoreHTTPSErrors: false });
    for (const [host, pathname] of [
      ['platform', '/'],
      ['console', '/'],
      ['api', '/.well-known/jwks.json'],
      ['remote', '/static-acceptance/v1/remote.js'],
    ]) {
      hostUnderTest = host;
      const response = await page.goto(`https://${host}.${root}${pathname}`, {
        timeout: 15_000,
        waitUntil: 'domcontentloaded',
      });
      if (response?.status() !== 200) {
        result.httpStatus = response?.status() ?? null;
        throw new Error('entrypoint unavailable');
      }
    }
    result.stages.push({ name: 'four-domain-tls-preflight', status: 'passed' });
  } catch (error) {
    result.blocker = {
      host: hostUnderTest,
      category:
        error?.message?.match(/(?:ERR_CERT_[A-Z_]+|SEC_ERROR_[A-Z_]+|SSL_ERROR_[A-Z_]+)/)?.[0] ??
        (hostUnderTest === 'browser-launch' ? 'browser-unavailable' : 'navigation-unavailable'),
    };
    result.stages.push({ name: 'four-domain-tls-preflight', status: 'blocked' });
  } finally {
    await browser?.close();
  }
  if (result.stages[0].status !== 'passed' || !credentialsReady) {
    if (!credentialsReady) result.blocker ??= { category: 'credentials-unavailable' };
    result.stages.push(
      { name: 'static-remote', status: 'not-run' },
      { name: 'session-security', status: 'not-run' },
    );
    continue;
  }
  const env = {
    ...process.env,
    SF_BROWSER: name === 'webkit' ? 'webkit' : 'chromium',
    SF_BROWSER_CHANNEL: channel ?? '',
    SF_BRAND_EVIDENCE_DIRECTORY: directory,
    SF_SESSION_EVIDENCE_DIRECTORY: path.join(directory, `session-security-${name}`),
  };
  for (const [stage, file] of [
    ['static-remote', 'static-remote.test.mjs'],
    ['session-security', 'browser-session-security.test.mjs'],
  ]) {
    const execution = spawnSync(
      process.execPath,
      [
        '--test',
        '--test-reporter=tap',
        new URL(`../integration-test/${file}`, import.meta.url).pathname,
      ],
      { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300_000 },
    );
    await writeFile(
      path.join(directory, `${stage}-${name}.log`),
      `${execution.stdout ?? ''}\n${execution.stderr ?? ''}`,
      { mode: 0o600 },
    );
    result.stages.push({ name: stage, status: execution.status === 0 ? 'passed' : 'failed' });
    console.info(`${execution.status === 0 ? 'PASS' : 'FAIL'}: development ${name} ${stage}`);
  }
  result.status = result.stages.every((stage) => stage.status === 'passed') ? 'passed' : 'failed';
}
await writeFile(
  path.join(directory, 'development-matrix.json'),
  JSON.stringify({ recordedAt: new Date().toISOString(), results }, null, 2) + '\n',
  { mode: 0o600 },
);
process.exitCode = results.every((result) => result.status === 'passed') ? 0 : 1;

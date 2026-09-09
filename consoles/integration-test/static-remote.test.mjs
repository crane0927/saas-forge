import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { edgeProbeRecords, isAnonymousRefreshError } from './browser-api-security.mjs';
import { chromium, firefox, webkit } from 'playwright';
import { verifyStaticRemoteRendering } from './static-remote-acceptance.mjs';
import { staticRemoteEvidence } from './static-remote-evidence.mjs';
const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';

test('Tenant Console executes fourth-domain static Remote through trusted TLS without credentials', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  await context.addCookies([
    {
      name: 'sf_static_probe',
      value: 'non-secret',
      domain: `remote.${rootDomain}`,
      path: '/',
      secure: true,
      sameSite: 'Strict',
    },
  ]);
  const page = await context.newPage();
  const remoteRecords = [];
  const cdp =
    (process.env.SF_BROWSER ?? 'chromium') === 'chromium'
      ? await context.newCDPSession(page)
      : null;
  await cdp?.send('Network.enable');
  const byRequest = new Map();
  const remoteRequestIds = new Set();
  const record = (requestId) => {
    if (!byRequest.has(requestId)) {
      const value = {};
      byRequest.set(requestId, value);
    }
    return byRequest.get(requestId);
  };
  cdp?.on('Network.requestWillBeSent', ({ requestId, request }) => {
    const url = new URL(request.url);
    if (url.hostname === `remote.${rootDomain}`) {
      if (!remoteRequestIds.has(requestId)) remoteRecords.push(record(requestId));
      remoteRequestIds.add(requestId);
      Object.assign(record(requestId), { path: url.pathname, method: request.method });
    }
  });
  cdp?.on('Network.requestWillBeSentExtraInfo', ({ requestId, headers }) => {
    // ExtraInfo 可能先于 requestWillBeSent 到达；先按 ID 保存，再筛选 Remote 请求。
    const names = Object.keys(headers).map((name) => name.toLowerCase());
    record(requestId).credentials = names.filter((name) =>
      ['cookie', 'authorization', 'x-sf-csrf'].includes(name),
    );
  });
  cdp?.on('Network.responseReceivedExtraInfo', ({ requestId, statusCode, headers }) => {
    const normalized = Object.fromEntries(
      Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]),
    );
    Object.assign(record(requestId), {
      status: statusCode,
      allowOrigin: normalized['access-control-allow-origin'] ?? null,
      allowCredentials: normalized['access-control-allow-credentials'] ?? null,
      contentType: normalized['content-type'] ?? null,
    });
  });
  const errors = [];
  page.on('pageerror', () => errors.push('pageerror'));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push('console-error');
  });

  let passed = false;
  let rendering = [];
  const pending = [];
  t.after(async () => {
    await Promise.all(pending);
    if (passed) assert.deepEqual(errors, []);
  });
  if (cdp === null)
    page.on('response', (response) => {
      const request = response.request();
      const url = new URL(request.url());
      if (url.hostname !== `remote.${rootDomain}`) return;
      pending.push(
        (async () => {
          const headers = await request.allHeaders();
          const r = await response.allHeaders();
          remoteRecords.push({
            path: url.pathname,
            method: request.method(),
            status: response.status(),
            credentials: ['cookie', 'authorization', 'x-sf-csrf'].filter((name) =>
              Object.hasOwn(headers, name),
            ),
            allowOrigin: r['access-control-allow-origin'] ?? null,
            allowCredentials: r['access-control-allow-credentials'] ?? null,
            contentType: r['content-type'] ?? null,
          });
        })().catch(() => errors.push('network-observation-failed')),
      );
    });
  {
    const directory = process.env.SF_BRAND_EVIDENCE_DIRECTORY;
    assert.ok(directory, 'static Remote acceptance requires a persistent evidence directory');
    const channel = process.env.SF_BROWSER_CHANNEL || process.env.SF_BROWSER || 'chromium';
    assert.ok(['chromium', 'chrome', 'msedge', 'webkit', 'firefox'].includes(channel));
    t.after(async () => {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      await writeFile(
        path.join(directory, `static-remote-${channel}.json`),
        `${JSON.stringify(
          staticRemoteEvidence({
            passed,
            records: remoteRecords,
            errors,
            rendering,
            tenantOrigin: `https://console.${rootDomain}`,
          }),
          null,
          2,
        )}\n`,
        { mode: 0o600 },
      );
    });
  }
  const defaultIcon = await context.request.get(`https://console.${rootDomain}/favicon.ico`);
  assert.equal(defaultIcon.status(), 204, 'default favicon probe has no independent brand');
  assert.equal((await defaultIcon.body()).length, 0);
  rendering = await verifyStaticRemoteRendering(page);
  assert.deepEqual(errors, []);
  await Promise.all(pending);
  assert.deepEqual(errors, []);
  const evidenceDeadline = Date.now() + 10_000;
  while (
    remoteRecords.some((item) => item.credentials === undefined || item.status === undefined) &&
    Date.now() < evidenceDeadline
  ) {
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.ok(remoteRecords.length >= 6);
  assert.ok(remoteRecords.every((item) => item.credentials?.length === 0));
  assert.ok(remoteRecords.every((item) => item.allowCredentials === null));
  for (const version of ['v1', 'v2']) {
    for (const file of ['remote.js', 'styles.css', 'image.svg']) {
      assert.ok(
        remoteRecords.some(
          (item) =>
            item.path === `/static-acceptance/${version}/${file}` &&
            item.status === 200 &&
            item.allowOrigin === `https://console.${rootDomain}`,
        ),
      );
    }
  }
  passed = true;
});

test(
  'Remote static CORS permits only Tenant origin and preserves version isolation',
  { timeout: 60_000 },
  async (t) => {
    const browser = await { chromium, firefox, webkit }[
      process.env.SF_BROWSER ?? 'chromium'
    ].launch({
      channel: process.env.SF_BROWSER_CHANNEL || undefined,
    });
    t.after(() => browser.close());
    const context = await browser.newContext({ ignoreHTTPSErrors: false });
    const policyEvidence = {
      status: 'failed',
      refusals: [],
      consoleErrors: [],
      unexpectedErrors: [],
    };
    t.after(async () => {
      await mkdir(process.env.SF_BRAND_EVIDENCE_DIRECTORY, { recursive: true, mode: 0o700 });
      await writeFile(
        path.join(
          process.env.SF_BRAND_EVIDENCE_DIRECTORY,
          `static-remote-policy-${process.env.SF_BROWSER_CHANNEL || process.env.SF_BROWSER || 'chromium'}.json`,
        ),
        JSON.stringify(policyEvidence, null, 2) + '\n',
        { mode: 0o600 },
      );
    });
    const tenant = await context.newPage();
    await tenant.goto(`https://console.${rootDomain}/`);
    const accepted = await tenant.evaluate(async (rootDomain) => {
      const versions = [];
      for (const version of ['v1', 'v2']) {
        const url = `https://remote.${rootDomain}/static-acceptance/${version}/remote.js`;
        const first = await fetch(url, { credentials: 'omit', cache: 'no-store' });
        const second = await fetch(url, { credentials: 'omit', cache: 'no-store' });
        versions.push({
          status: first.status,
          stable: (await first.text()) === (await second.text()),
        });
      }
      const missing = await fetch(`https://remote.${rootDomain}/static-acceptance/v1/missing.js`, {
        credentials: 'omit',
      });
      return {
        versions,
        missing: { status: missing.status, html: /<!doctype|<html/i.test(await missing.text()) },
      };
    }, rootDomain);
    assert.deepEqual(accepted, {
      versions: [
        { status: 200, stable: true },
        { status: 200, stable: true },
      ],
      missing: { status: 404, html: false },
    });

    const refusals = policyEvidence.refusals;
    policyEvidence.versions = accepted;
    const hashes = await tenant.evaluate(async (rootDomain) => {
      const values = {};
      for (const version of ['v1', 'v2']) {
        values[version] = {};
        for (const file of ['remote.js', 'styles.css', 'image.svg']) {
          const response = await fetch(
            `https://remote.${rootDomain}/static-acceptance/${version}/${file}`,
            { credentials: 'omit', cache: 'no-store' },
          );
          if (!response.ok) throw new Error('artifact unavailable');
          const hash = await crypto.subtle.digest('SHA-256', await response.arrayBuffer());
          values[version][file] = Array.from(new Uint8Array(hash), (value) =>
            value.toString(16).padStart(2, '0'),
          ).join('');
        }
      }
      return values;
    }, rootDomain);
    assert.deepEqual(
      hashes,
      JSON.parse(
        await readFile(
          new URL('../static-remote-acceptance/checksums.json', import.meta.url),
          'utf8',
        ),
      ),
    );
    policyEvidence.artifactHashes = hashes;
    for (const [originName, url] of [
      ['platform', `https://platform.${rootDomain}/`],
      ['api', `https://api.${rootDomain}/.well-known/jwks.json`],
      ['null', 'data:text/html,<!doctype html><title>Opaque Origin probe</title>'],
    ]) {
      const page = await context.newPage();
      await page.goto(url);
      page.on('console', (message) => {
        if (message.type() !== 'error') return;
        const text = message.text();
        const location = message.location().url;
        const sourceOrigin = originName === 'null' ? 'null' : new URL(url).origin;
        const webkitRefusal =
          text ===
          `Origin ${sourceOrigin} is not allowed by Access-Control-Allow-Origin. Status code: 200`;
        if (isAnonymousRefreshError(message)) {
          policyEvidence.consoleErrors.push({
            origin: originName,
            category: 'anonymous-refresh-401',
          });
          return;
        }
        if (
          webkitRefusal ||
          ((text.includes('remoteProbe=') || location.includes('remoteProbe=')) &&
            /CORS|access control|access-control|ERR_FAILED|Failed to load resource/i.test(text))
        )
          policyEvidence.consoleErrors.push({
            origin: originName,
            category: 'expected-cors-refusal',
          });
        else policyEvidence.unexpectedErrors.push('unexpected-console-error');
      });
      const probe = randomUUID();
      const rejected = await page.evaluate(
        async ({ rootDomain, probe }) => {
          try {
            await fetch(
              `https://remote.${rootDomain}/static-acceptance/v1/remote.js?remoteProbe=${probe}`,
              {
                credentials: 'omit',
                cache: 'no-store',
              },
            );
            return false;
          } catch {
            return true;
          }
        },
        { rootDomain, probe },
      );
      assert.equal(rejected, true, `${originName} origin should be rejected by CORS`);
      const deadline = Date.now() + 10_000;
      let response;
      while (!response && Date.now() < deadline) {
        response = edgeProbeRecords(probe, 'acceptance-remote-security').at(-1);
        if (!response) await new Promise((resolve) => setTimeout(resolve, 100));
      }
      assert.ok(response, 'CORS refusal requires correlated Edge response evidence');
      assert.equal(response.status, 200);
      assert.equal(response.allowOrigin, null);
      assert.equal(response.allowCredentials, null);
      assert.deepEqual(response.credentialHeaders, []);
      refusals.push({ origin: originName, browserReadBlocked: true, ...response });
    }
    assert.deepEqual(policyEvidence.unexpectedErrors, []);
    policyEvidence.status = 'passed';
  },
);

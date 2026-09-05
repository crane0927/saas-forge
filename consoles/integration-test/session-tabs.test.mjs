/* global window, indexedDB, document */
import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
import { chromium, firefox, webkit } from 'playwright';

test('native browser tabs share refresh, hide stale Tenant UI, and retry snapshot reads', async (t) => {
  const server = await createServer({
    configFile: false,
    plugins: [react()],
    resolve: { dedupe: ['react', 'react-dom'] },
    server: { host: '127.0.0.1', port: 0 },
  });
  t.after(() => server.close());
  await server.listen();
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL,
  });
  t.after(() => browser.close());
  const context = await browser.newContext();
  const errors = [];
  const consoleMessages = [];
  context.on('page', (page) => page.on('pageerror', (error) => errors.push(error.message)));
  context.on('page', (page) =>
    page.on('console', (message) => consoleMessages.push(message.text())),
  );
  await context.addInitScript(() => {
    window.sessionMessages = [];
    const NativeChannel = BroadcastChannel;
    window.BroadcastChannel = class extends NativeChannel {
      postMessage(data) {
        window.sessionMessages.push(structuredClone(data));
        super.postMessage(data);
      }
    };
  });
  let refreshes = 0;
  let snapshots = 0;
  const requests = [];
  let releaseLogout;
  const logoutGate = new Promise((resolve) => {
    releaseLogout = resolve;
  });
  let logouts = 0;
  let start;
  const ready = new Promise((resolve) => {
    start = resolve;
  });
  await context.route('https://api.example.test/**', async (route) => {
    const request = route.request();
    requests.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() });
    if (request.url().endsWith('/refresh')) {
      await ready;
      refreshes += 1;
      await route.fulfill({
        json: {
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: `acceptance-token-${refreshes}`,
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: tenantContext(),
        },
      });
    } else if (request.url().endsWith('/context')) {
      snapshots += 1;
      if (snapshots === 1)
        await route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          headers: { 'Retry-After': '0' },
          json: {
            type: 'urn:saasforge:problem:token-revocation-status-unavailable',
            title: 'Unavailable',
            detail: 'private acceptance detail',
            status: 503,
            code: 'TOKEN_REVOCATION_STATUS_UNAVAILABLE',
            traceId: '0123456789abcdef0123456789abcdef',
          },
        });
      else await route.fulfill({ json: tenantContext() });
    } else if (request.url().endsWith('/logout')) {
      logouts += 1;
      if (logouts === 1) {
        await logoutGate;
        await route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          json: {
            type: 'urn:saasforge:problem:revocation-unavailable',
            title: 'Unavailable',
            detail: 'private acceptance detail',
            status: 503,
            code: 'REVOCATION_UNAVAILABLE',
            traceId: '0123456789abcdef0123456789abcdef',
          },
        });
      } else await route.fulfill({ status: 204 });
    } else
      await route.fulfill({
        json: {
          clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
          displayName: 'Client',
          clientType: 'RUNTIME_SERVICE',
          allowedScopes: [],
          status: 'ACTIVE',
          createdAt: '2026-09-01T00:00:00Z',
          updatedAt: '2026-09-01T00:00:00Z',
        },
      });
  });
  const first = await context.newPage();
  const second = await context.newPage();
  const address = server.httpServer.address();
  const url = `http://127.0.0.1:${address.port}/tenant-console-shell/test/session-tabs.html`;
  await Promise.all([first.goto(url), second.goto(url)]);
  await Promise.all(
    [first, second].map((page) =>
      page.waitForFunction(() => window.sessionAcceptance !== undefined),
    ),
  );
  start();
  // 哪个页先持锁不确定，按真实 UI 找出发起页和接收页。
  await Promise.all(
    [first, second].map((page) =>
      page.waitForFunction(
        () =>
          (window.sessionAcceptance?.state().status === 'authenticated' &&
            window.sessionAcceptance?.state().transition === null) ||
          window.sessionAcceptance?.state().synchronizationProblem,
      ),
    ),
  );
  const receiver = (await first.evaluate(
    () => window.sessionAcceptance.state().synchronizationProblem,
  ))
    ? first
    : second;
  const sender = receiver === first ? second : first;
  await receiver.getByRole('heading', { name: '暂时无法恢复会话' }).waitFor();
  assert.equal(await receiver.getByRole('heading', { name: '受保护的工作台' }).count(), 0);
  await receiver.getByRole('button', { name: '重试恢复' }).click();
  await receiver.getByRole('heading', { name: '受保护的工作台' }).waitFor();
  assert.equal(refreshes, 1);
  assert.equal(snapshots, 2);
  await Promise.all(
    [sender, receiver].map((page) => page.evaluate(() => window.sessionAcceptance.advance())),
  );
  const reads = await Promise.all(
    [sender, receiver].map((page) => page.evaluate(() => window.sessionAcceptance.read())),
  );
  assert.ok(reads.every((result) => result.ok));
  assert.equal(refreshes, 2);
  assert.ok(
    requests
      .filter((request) => request.path.endsWith('/refresh'))
      .every((request) => request.body.sessionSlot === 'TENANT'),
  );
  const isolated = await context.newPage();
  await isolated.goto(url.replace('127.0.0.1', 'localhost') + '?slot=PLATFORM');
  await isolated.getByRole('heading', { name: '受保护的工作台' }).waitFor();
  assert.equal(refreshes, 3);
  assert.equal(requests.filter((request) => request.body?.sessionSlot === 'PLATFORM').length, 1);
  const logout = sender.evaluate(() => window.sessionAcceptance.logout());
  await receiver.waitForFunction(() => window.sessionAcceptance.state().status === 'logoutPending');
  assert.equal((await receiver.evaluate(() => window.sessionAcceptance.read())).ok, false);
  await receiver.reload();
  await receiver.waitForFunction(
    () => window.sessionAcceptance?.state().status === 'logoutPending',
  );
  assert.equal(refreshes, 3);
  releaseLogout();
  assert.equal((await logout).ok, false);
  assert.equal((await receiver.evaluate(() => window.sessionAcceptance.logout())).ok, true);
  await sender.waitForFunction(() => window.sessionAcceptance.state().status === 'anonymous');
  assert.equal(
    await isolated.evaluate(() => window.sessionAcceptance.state().status),
    'authenticated',
  );
  assert.equal(logouts, 2);
  for (const page of [sender, receiver, isolated]) {
    const persisted = await page.evaluate(async () => ({
      local: Object.entries(localStorage),
      session: Object.entries(sessionStorage),
      databases: await indexedDB.databases(),
      cookie: document.cookie,
    }));
    assert.doesNotMatch(
      JSON.stringify(persisted),
      /acceptance-token|private acceptance|membershipId|tenantId|password|@/,
    );
    assert.deepEqual(persisted.session, []);
    assert.deepEqual(persisted.databases, []);
    assert.equal(persisted.cookie, '');
    assert.ok(
      persisted.local.every(
        ([key, value]) =>
          (key === 'sf:ui:locale' && (value === 'zh-CN' || value === 'en-US')) ||
          (key.startsWith('sf:session:https://api.example.test:') &&
            (/^\d+$/.test(value) || value === 'true' || value === 'false')),
      ),
    );
    const messages = await page.evaluate(() => window.sessionMessages);
    for (const message of messages)
      assert.deepEqual(
        Object.keys(message).sort(),
        message.event === 'refresh-succeeded'
          ? ['accessToken', 'contextType', 'event', 'expiresAt', 'generation']
          : ['contextType', 'event', 'generation'],
      );
  }
  assert.doesNotMatch(
    consoleMessages.join('\n'),
    /acceptance-token|private acceptance|membershipId|password/,
  );
  assert.deepEqual(errors, []);
});

function tenantContext() {
  const current = {
    membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
    tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
    tenantDisplayName: 'Acceptance Tenant',
  };
  return {
    ...current,
    accessibleMemberships: [current],
    brandProfile: {
      displayName: 'Acceptance Brand',
      primaryColor: '#155EEF',
      accentColor: '#7A5AF8',
    },
  };
}

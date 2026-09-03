/* global window */
import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { chromium, firefox, webkit } from 'playwright';

// 聚焦默认应用入口的浏览器能力接线；模拟 HTTP 仅用于快速回归，不替代真实 TLS/IAM 验收。
for (const [application, directory, heading] of [
  ['Platform', 'platform-console', 'Platform 总览'],
  ['Tenant', 'tenant-console-shell', 'Tenant 工作台'],
]) {
  test(`default ${application} Console entry coordinates a single refresh across native tabs`, async (t) => {
    const root = fileURLToPath(new URL(`../${directory}`, import.meta.url));
    const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 } });
    t.after(() => server.close());
    await server.listen();
    const browser = await { chromium, firefox, webkit }[
      process.env.SF_BROWSER ?? 'chromium'
    ].launch({
      channel: process.env.SF_BROWSER_CHANNEL || undefined,
    });
    t.after(() => browser.close());
    const context = await browser.newContext();
    await context.addInitScript(() => {
      // 观察实际 Refresh；无协调能力的旧入口也能到达屏障并暴露重复请求。
      window.sessionOperationStarted = false;
      const nativeFetch = window.fetch.bind(window);
      window.fetch = (...args) => {
        if (String(args[0]).endsWith('/api/v1/auth/refresh')) window.sessionOperationStarted = true;
        return nativeFetch(...args);
      };
    });
    let release;
    const ready = new Promise((resolve) => {
      release = resolve;
    });
    let refreshes = 0;
    const tenantContext = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6077',
      tenantDisplayName: 'Coordination Tenant',
      accessibleMemberships: [
        {
          membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
          tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6077',
          tenantDisplayName: 'Coordination Tenant',
        },
      ],
    };
    await context.route('https://api.saasforge.test/api/v1/auth/**', async (route) => {
      if (new URL(route.request().url()).pathname === '/api/v1/auth/refresh') {
        assert.equal(route.request().postDataJSON().sessionSlot, application.toUpperCase());
        refreshes += 1;
        await ready;
        await route.fulfill({
          json: {
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'focused-regression-token',
            tokenType: 'Bearer',
            expiresIn: 120,
            ...(application === 'Tenant' ? { tenantContext } : {}),
          },
        });
      } else {
        assert.equal(new URL(route.request().url()).pathname, '/api/v1/auth/context');
        await route.fulfill({ json: tenantContext });
      }
    });
    const pages = await Promise.all([context.newPage(), context.newPage()]);
    const address = server.httpServer.address();
    await Promise.all(pages.map((page) => page.goto(`http://127.0.0.1:${address.port}/`)));
    // 原生队列证明另一页已经开始恢复；不要覆写 LockManager 实例方法，
    // WebKit 下该观察器可能漏报，而公开 query 已显示持锁者和等待者。
    const lockName = `sf:session:https://api.saasforge.test:${application.toUpperCase()}`;
    await Promise.all(
      pages.map((page) =>
        page.waitForFunction(async (name) => {
          if (window.sessionOperationStarted) return true;
          if (navigator.locks === undefined) return false;
          const locks = await navigator.locks.query();
          return (
            locks.held.some((lock) => lock.name === name) &&
            locks.pending.some((lock) => lock.name === name)
          );
        }, lockName),
      ),
    );
    release();
    for (const page of pages) {
      await page.getByRole('heading', { name: heading, exact: true }).waitFor();
    }
    assert.equal(refreshes, 1, 'one coordinated browser session must perform one refresh');
  });
}

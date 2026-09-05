/* global document */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';

export async function verifyRequestProblemSurfaces(browser) {
  for (const [host, application] of [
    ['platform', 'Platform Console'],
    ['console', 'Tenant Console'],
  ]) {
    const context = await browser.newContext({
      ignoreHTTPSErrors: false,
      viewport: { width: 390, height: 844 },
    });
    // 故障断言使用中文文案，不能依赖执行浏览器的系统语言。
    await context.addInitScript(() => {
      localStorage.setItem('sf:ui:locale', 'zh-CN');
    });
    try {
      const page = await context.newPage();
      const marker = `private-response-${randomUUID()}`;
      let leaked = false;
      page.on('console', (message) => {
        leaked ||= message.text().includes(marker);
      });
      const coldRecovery = page.waitForRequest(
        (request) =>
          new URL(request.url()).pathname === '/api/v1/auth/refresh' && request.method() === 'POST',
      );
      await page.goto(`https://${host}.${rootDomain}/`);
      assert.equal(
        (await coldRecovery).postDataJSON().sessionSlot,
        host === 'platform' ? 'PLATFORM' : 'TENANT',
      );
      await page.getByRole('heading', { name: `登录 ${application}`, exact: true }).waitFor();
      let refreshes = 0;
      page.on('request', (request) => {
        if (new URL(request.url()).pathname === '/api/v1/auth/refresh') refreshes += 1;
      });
      for (const [kind, code] of [
        ['unauthorized', 'ACCESS_TOKEN_INVALID'],
        ['problem', 'BACKEND_UNAVAILABLE'],
        ['malformed', 'INVALID_SERVICE_RESPONSE'],
        ['network', 'NETWORK_UNAVAILABLE'],
      ]) {
        let requests = 0;
        const status = kind === 'unauthorized' ? 401 : 503;
        await page.route('**/api/v1/auth/login', async (route) => {
          requests += 1;
          // 故障注入只用于负向；正常认证仍由其他用例经过真实 Gateway/IAM。
          if (kind === 'network') await route.abort('failed');
          else if (kind === 'malformed')
            await route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({ unexpected: marker }),
            });
          else
            await route.fulfill({
              status,
              contentType: 'application/problem+json',
              body: JSON.stringify({
                type: `urn:saasforge:problem:${code.toLowerCase().replaceAll('_', '-')}`,
                status,
                code,
                title: marker,
                detail: marker,
                traceId: '11111111111111111111111111111111',
              }),
            });
        });
        await page.getByLabel(/^邮箱/).fill('fault-only@example.test');
        // 此凭据仅发送到上面的故障处理器；不尝试登录真实账号。
        await page.getByLabel(/^密码/).fill('fault-only-input');
        await page.getByLabel(/^密码/).press('Enter');
        await page
          .getByRole('alert')
          .filter({ hasText: `错误代码：${code}` })
          .waitFor();
        assert.equal(requests, 1, 'a failed unsafe login must not be replayed');
        assert.equal(refreshes, 0, 'an unsafe login failure must not trigger recovery or replay');
        assert.equal((await page.locator('body').innerText()).includes(marker), false);
        assert.equal((await page.getByLabel(/^密码/).inputValue()).length, 0);
        assert.equal(
          await page.getByRole('heading', { name: `登录 ${application}`, exact: true }).count(),
          1,
        );
        assert.equal(
          await page.evaluate(() => document.documentElement.scrollWidth <= globalThis.innerWidth),
          true,
        );
        assert.equal(
          await page.evaluate(
            (rootDomain) =>
              sessionStorage.length === 0 &&
              document.cookie === '' &&
              Object.keys(localStorage).every(
                (key) =>
                  (key === 'sf:ui:locale' &&
                    ['zh-CN', 'en-US'].includes(localStorage.getItem(key))) ||
                  ['PLATFORM', 'TENANT'].some((slot) =>
                    ['generation', 'logoutPending'].some(
                      (field) => key === `sf:session:https://api.${rootDomain}:${slot}:${field}`,
                    ),
                  ),
              ),
            rootDomain,
          ),
          true,
        );
        await page.unroute('**/api/v1/auth/login');
      }
      assert.equal(
        leaked,
        false,
        'original Problem and malformed payloads must not reach diagnostics',
      );
    } finally {
      await context.close();
    }
  }
}

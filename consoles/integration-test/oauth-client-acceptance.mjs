import assert from 'node:assert/strict';

// Browser plugin not available. 复用正式生产页面、生成 Client 和真实 IAM；故障注入单独断言。
export async function verifyOAuthClients({
  browser,
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
}) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 1440, height: 960 },
  });
  const base = `https://platform.${rootDomain}`;
  const name = `oauth-list-${Date.now()}`;
  const page = await context.newPage();
  try {
    await page.goto(base);
    await selectLocale(page, '简体中文');
    await login(page, email, password, 'zh-CN');
    const fixture = await context.newPage();
    try {
      await fixture.goto(`${base}/acceptance-client.html`);
      const prepared = await fixture.evaluate(
        async ({ rootDomain, name }) => {
          const { createAuthenticationRuntimeAfterConfig } = await import('/acceptance-runtime.js');
          const created = createAuthenticationRuntimeAfterConfig(
            { ok: true, config: { schemaVersion: 1, apiBaseUrl: `https://api.${rootDomain}` } },
            { realm: globalThis, intent: 'PLATFORM', fetch: (input, init) => fetch(input, init) },
          );
          if (!created.ok || !(await created.runtime.recover()).ok) return false;
          // 仅通过已有正式创建能力准备分页数据；一次性 Secret 不离开此作用域。
          for (let i = 0; i < 51; i++) {
            const result = await created.runtime.client.createOAuthClient({
              request: { displayName: `${name}-${i}`, allowedScopes: new Set(['runtime:read']) },
            });
            if (!result.ok) return false;
          }
          return true;
        },
        { rootDomain, name },
      );
      assert.equal(prepared, true, 'real IAM must prepare 51 clients');
    } finally {
      await fixture.close();
    }
    const reads = [];
    page.on('response', (response) => {
      if (
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname.startsWith('/api/v1/platform/oauth-clients')
      )
        reads.push(response);
    });
    await page.goto(`${base}/oauth-clients`);
    await page.getByRole('textbox', { name: '名称', exact: true }).fill(name);
    await page.getByRole('combobox', { name: '类型', exact: true }).click();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await page.getByRole('combobox', { name: '状态', exact: true }).click();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.getByRole('cell', { name: `${name}-0`, exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '查看详情', exact: true }).count(), 50);
    await page.getByRole('button', { name: '下一页', exact: true }).click();
    await page.getByRole('cell', { name: `${name}-50`, exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '查看详情', exact: true }).count(), 1);
    await page.getByRole('button', { name: '查看详情', exact: true }).click();
    await page.getByText('runtime:read', { exact: true }).waitFor();
    const detailUrl = page.url();
    await page.reload();
    await page.getByText(`${name}-50`, { exact: true }).waitFor();
    await selectLocale(page, 'English');
    await page.getByRole('heading', { name: 'OAuth Client details', exact: true }).waitFor();
    await accessibility(page, 'OAuth Client details');
    await capture(page, 'oauth-client-details-en');
    await safeStorage(page);
    for (const response of reads) {
      assert.equal(response.status(), 200);
      const text = await response.text();
      assert.doesNotMatch(text, /"(?:clientSecret|secretDigest|secret_digest|accessToken)"/);
    }
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(page, email, password, 'en-US');
    await page.goto(detailUrl);
    await page.getByText(`${name}-50`, { exact: true }).waitFor();
    // 明确是浏览器 HTTP 故障注入；真实拒绝授权由 AuthenticationHttpIT 覆盖。
    for (const status of [403, 503]) {
      await page.route('**/api/v1/platform/oauth-clients?*', (route) =>
        route.fulfill({
          status,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type:
              status === 403
                ? 'urn:saasforge:problem:platform-admin-required'
                : 'urn:saasforge:problem:upstream-unavailable',
            title: 'Unavailable',
            status,
            code: status === 403 ? 'PLATFORM_ADMIN_REQUIRED' : 'UPSTREAM_UNAVAILABLE',
            detail: 'Injected read failure',
            traceId: '0123456789abcdef0123456789abcdef',
          }),
        }),
      );
      await page.goto(`${base}/oauth-clients`);
      await page
        .getByText(
          status === 403
            ? 'The current identity is not authorized as a platform administrator.'
            : 'OAuth Client unavailable',
          { exact: true },
        )
        .waitFor();
      assert.equal(await page.getByText('No matching OAuth Clients.', { exact: true }).count(), 0);
      await page.unroute('**/api/v1/platform/oauth-clients?*');
    }
  } finally {
    await context.close();
  }
}

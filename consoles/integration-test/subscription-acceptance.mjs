import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';

// Browser plugin not available. 复用正式 Chrome/Fresh HTTPS 与真实服务验收环境。
export async function verifySubscription({
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
}) {
  const profile = await mkdtemp(path.join(tmpdir(), 'sf-subscription-'));
  const base = `https://platform.${rootDomain}`;
  const project = process.env.SF_ACCEPTANCE_PROJECT;
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  function fixture(database, sql) {
    execFileSync(
      'docker',
      [
        'exec',
        '-i',
        `${project}-postgres-1`,
        'psql',
        '-U',
        'saasforge_console_e2e',
        '-d',
        database,
        '-v',
        'ON_ERROR_STOP=1',
      ],
      { input: sql, stdio: ['pipe', 'pipe', 'pipe'] },
    );
  }
  const launch = () =>
    chromium.launchPersistentContext(profile, {
      channel: 'chrome',
      headless: true,
      ignoreHTTPSErrors: false,
      locale: 'zh-CN',
      viewport: { width: 1440, height: 960 },
    });
  let context;
  const errors = [];
  try {
    context = await launch();
    let page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(base);
    await login(page, email, password, 'zh-CN');
    await page.goto(`${base}/tenants/new`);
    const form = page.getByRole('form', { name: '创建 Tenant', exact: true });
    await form.getByRole('textbox', { name: '名称', exact: false }).fill('Subscription acceptance');
    await form.getByRole('button', { name: '创建 Tenant', exact: true }).click();
    await page.waitForURL(/\/tenants\/[0-9a-f-]{36}$/);
    const tenantUrl = page.url();
    await page.getByText('尚无 Subscription', { exact: true }).waitFor();
    await page.getByRole('combobox', { name: 'Plan', exact: true }).click();
    await page.getByText('Browser Plan (browser-plan) — 1', { exact: true }).click();
    let creates = 0;
    let created;
    await page.route('**/api/v1/platform/tenants/*/subscriptions', async (route) => {
      creates++;
      const response = await route.fetch();
      assert.equal(response.status(), 201);
      created = await response.json();
      // 同一真实浏览器请求以另一个逻辑 Key 再提交，确认服务端唯一性而非仅验证按钮消失。
      const duplicate = await route.fetch({
        headers: {
          ...route.request().headers(),
          'idempotency-key': '019535d9-0000-7000-8000-000000000975',
        },
      });
      assert.equal(duplicate.status(), 409);
      assert.equal((await duplicate.json()).code, 'SUBSCRIPTION_ALREADY_EXISTS');
      await route.abort('failed');
    });
    await page.getByRole('button', { name: '创建首个 Subscription', exact: true }).click();
    await page.getByText('订阅创建结果待确认', { exact: true }).waitFor();
    assert.equal(creates, 1);
    assert.ok(created.id);
    await page.getByText('max_users 已用量', { exact: true }).waitFor();
    await capture(page, 'issue-175-response-lost');
    await page.unroute('**/api/v1/platform/tenants/*/subscriptions');
    // 在隔离 Fresh 库放入另一 actor 的恢复记录；当前会话必须看不到该记录。
    fixture(
      'entitlement_db',
      `INSERT INTO subscription_recovery(actor_identity_id, idempotency_key, tenant_id, plan_id, ends_at, created_at, replay_until, response_body)
      SELECT '019535d9-0000-7000-8000-000000000976', idempotency_key, tenant_id, plan_id, ends_at, created_at, replay_until, response_body
      FROM subscription_recovery WHERE tenant_id = '${created.tenantId}';`,
    );
    await page.reload();
    await page.getByText(created.id, { exact: true }).waitFor();
    await page.getByRole('button', { name: '读取操作记录', exact: true }).click();
    await page.getByRole('button', { name: '查看订阅', exact: true }).first().waitFor();
    assert.equal(await page.getByRole('button', { name: '查看订阅', exact: true }).count(), 1);
    await page.getByRole('button', { name: '查看订阅', exact: true }).click();
    await page.getByText('查询时有效', { exact: true }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '创建首个 Subscription', exact: true }).count(),
      0,
    );
    await page.reload();
    await accessibility(page, 'Tenant 详情');
    assert.equal(
      await page
        .locator('dl > div')
        .filter({ has: page.locator('dt', { hasText: 'max_users 上限' }) })
        .locator('dd')
        .textContent(),
      '1',
    );
    assert.equal(
      await page
        .locator('dl > div')
        .filter({ has: page.locator('dt', { hasText: 'max_users 已用量' }) })
        .locator('dd')
        .textContent(),
      '0',
    );
    await safeStorage(page);
    await capture(page, 'issue-175-subscription-detail');
    await page.setViewportSize({ width: 320, height: 900 });
    assert.equal(
      await page.evaluate(
        () => globalThis.document.documentElement.scrollWidth <= globalThis.innerWidth,
      ),
      true,
    );
    await capture(page, 'issue-175-narrow-detail');
    await page.setViewportSize({ width: 1440, height: 960 });
    // 真实已确认 Tenant 在 Entitlement 请求失败时仍保留；失败不能伪装成空订阅。
    await page.route('**/api/v1/platform/tenants/*/subscription', (route) => route.abort('failed'));
    await page.getByRole('button', { name: '重试权益读取', exact: true }).click();
    await page.getByText('权益暂时无法读取', { exact: true }).waitFor();
    await page.getByText('Subscription acceptance', { exact: true }).waitFor();
    assert.equal(await page.getByText('尚无 Subscription', { exact: true }).count(), 0);
    await capture(page, 'issue-175-partial-failure');
    await page.unroute('**/api/v1/platform/tenants/*/subscription');
    await page.getByRole('button', { name: '重试权益读取', exact: true }).click();
    await page.getByText(created.id, { exact: true }).waitFor();
    await selectLocale(page, 'English');
    await page.getByText('Effective when observed', { exact: true }).waitFor();
    await page.reload();
    await accessibility(page, 'Tenant details');
    await capture(page, 'issue-175-english');
    fixture(
      'entitlement_db',
      `UPDATE subscriptions SET created_at = now() - interval '2 days', ends_at = now() - interval '1 day' WHERE id = '${created.id}';`,
    );
    await page.getByRole('button', { name: 'Retry entitlement read', exact: true }).click();
    await page.getByText('Expired', { exact: true }).waitFor();
    await page.getByText('ACTIVE', { exact: true }).waitFor();
    await capture(page, 'issue-175-expired-subscription');
    await context.close();
    context = await launch();
    page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(tenantUrl);
    await page.getByText(created.id, { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(page, email, password, 'en-US');
    await page.goto(tenantUrl);
    await page.getByRole('button', { name: 'Read operation records', exact: true }).click();
    await page.getByRole('button', { name: 'View subscription', exact: true }).click();
    await page.getByText(created.id, { exact: true }).waitFor();
    await safeStorage(page);
    await selectLocale(page, '简体中文');
    for (const rejection of ['zero', 'expired', 'closed']) {
      await page.goto(`${base}/tenants/new`);
      const createTenant = page.getByRole('form', { name: '创建 Tenant', exact: true });
      await createTenant
        .getByRole('textbox', { name: '名称', exact: false })
        .fill(`Rejected subscription ${rejection}`);
      await createTenant.getByRole('button', { name: '创建 Tenant', exact: true }).click();
      await page.waitForURL(/\/tenants\/[0-9a-f-]{36}$/);
      const target = page.url().split('/').at(-1);
      assert.match(target, /^[0-9a-f-]{36}$/);
      await page.getByRole('combobox', { name: 'Plan', exact: true }).click();
      await page.getByText('Browser Plan (browser-plan) — 1', { exact: true }).click();
      // 页面读取之后改变隔离夹具，证明写入端再次检查最新资格，不能依赖旧页面快照。
      if (rejection === 'zero')
        fixture(
          'entitlement_db',
          "UPDATE plans SET plan_status = 'ACTIVE' WHERE id = '019535d9-0000-7000-8000-000000000974';",
        );
      else
        fixture(
          'tenant_access_db',
          `UPDATE tenants SET ${rejection === 'expired' ? "expires_at = now() - interval '1 second'" : "tenant_status = 'CLOSED'"} WHERE id = '${target}';`,
        );
      let rejectionCode;
      await page.route('**/api/v1/platform/tenants/*/subscriptions', async (route) => {
        const response = await route.fetch(
          rejection === 'zero'
            ? {
                postData: JSON.stringify({
                  ...route.request().postDataJSON(),
                  planId: '019535d9-0000-7000-8000-000000000974',
                }),
              }
            : undefined,
        );
        rejectionCode = (await response.json()).code;
        assert.equal(response.status(), rejection === 'zero' ? 400 : 409);
        await route.fulfill({ response });
      });
      await page.getByRole('button', { name: '创建首个 Subscription', exact: true }).click();
      await page.getByText('订阅创建结果待确认', { exact: true }).waitFor();
      assert.equal(
        rejectionCode,
        rejection === 'zero'
          ? 'PLAN_INVALID'
          : rejection === 'expired'
            ? 'TENANT_EXPIRY_REACHED'
            : 'TENANT_INVALID_STATE',
      );
      await page.unroute('**/api/v1/platform/tenants/*/subscriptions');
      await page.reload();
      await page.getByText('尚无 Subscription', { exact: true }).waitFor();
      await capture(page, `issue-175-rejected-${rejection}`);
    }
    assert.deepEqual(errors, []);
  } finally {
    await context?.close();
    await rm(profile, { recursive: true, force: true });
  }
}

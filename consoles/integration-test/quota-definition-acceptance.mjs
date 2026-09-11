import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { chromium } from 'playwright';

// Browser plugin not available. 所有业务响应来自真实服务；仅在提交后丢弃响应。
export async function verifyQuotaDefinition({
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
}) {
  const profile = await mkdtemp(path.join(tmpdir(), 'sf-quota-definition-'));
  const base = `https://platform.${rootDomain}`;
  const launch = () =>
    chromium.launchPersistentContext(profile, {
      channel: 'chrome',
      headless: true,
      ignoreHTTPSErrors: false,
      locale: 'zh-CN',
      viewport: { width: 1440, height: 960 },
    });
  let context;
  let id;
  const errors = [];
  try {
    context = await launch();
    let page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(base);
    await login(page, email, password, 'zh-CN');
    await page.goto(`${base}/quota-definitions`);
    await accessibility(page, '额度定义');
    await page.getByRole('button', { name: '创建 max_users', exact: true }).click();
    await accessibility(page, '创建 max_users');
    let creates = 0;
    await page.route('**/api/v1/platform/quota-definitions', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      creates += 1;
      const response = await route.fetch();
      assert.equal(response.status(), 201);
      id = (await response.json()).id;
      await route.abort('failed');
    });
    await page.getByRole('button', { name: '创建 max_users', exact: true }).dblclick();
    await page.getByText('操作结果待确认', { exact: true }).waitFor();
    assert.equal(creates, 1);
    await capture(page, 'issue-173-create-response-lost');
    await page.unroute('**/api/v1/platform/quota-definitions');
    await page.reload();
    await page.getByRole('button', { name: '复用 max_users', exact: true }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '创建 max_users', exact: true }).count(),
      0,
    );
    await page.getByRole('button', { name: '读取操作记录', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: '创建 max_users' })
      .getByRole('button', { name: '查看额度定义' })
      .click();
    await accessibility(page, '额度定义详情');
    await page.getByText(id, { exact: true }).waitFor();
    let activations = 0;
    await page.route('**/api/v1/platform/quota-definitions/*/activations', async (route) => {
      activations += 1;
      const response = await route.fetch();
      assert.equal(response.status(), 200);
      await route.abort('failed');
    });
    await page.getByRole('button', { name: '激活 max_users', exact: true }).dblclick();
    await page.getByText('操作结果待确认', { exact: true }).waitFor();
    assert.equal(activations, 1);
    await capture(page, 'issue-173-activation-response-lost');
    await page.unroute('**/api/v1/platform/quota-definitions/*/activations');
    await page.reload();
    await page.getByText('已激活', { exact: true }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: '激活 max_users', exact: true }).count(),
      0,
    );
    await page.getByRole('button', { name: '读取操作记录', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: '激活 max_users' })
      .getByText('已提交', { exact: false })
      .waitFor();
    await capture(page, 'issue-173-authoritative-detail');
    await safeStorage(page);
    await page.getByRole('button', { name: '返回额度定义', exact: true }).click();
    await page.getByRole('textbox', { name: '编码', exact: true }).fill('max_users');
    await page.getByRole('combobox', { name: '状态', exact: true }).press('Enter');
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: '已激活' })
      .click();
    const query = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/v1/platform/quota-definitions' &&
        url.searchParams.get('status') === 'ACTIVE' &&
        url.searchParams.get('code') === 'max_users'
      );
    });
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.equal((await query).status(), 200);
    await page.getByRole('table').getByText('max_users', { exact: true }).waitFor();
    await selectLocale(page, 'English');
    await page.getByRole('button', { name: 'Reuse max_users', exact: true }).click();
    await accessibility(page, 'Quota definition details');
    await page.getByText('Active', { exact: true }).waitFor();
    await capture(page, 'issue-173-english-detail');
    await context.close();
    context = await launch();
    page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(`${base}/quota-definitions`);
    await page.getByRole('button', { name: 'Read operation records', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: 'Activate max_users' })
      .getByRole('button', { name: 'View quota definition' })
      .click();
    await page.getByText(id, { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(page, email, password, 'en-US');
    await page.goto(`${base}/quota-definitions/new`);
    await page.getByRole('button', { name: 'Reuse max_users', exact: true }).click();
    await page.getByText(id, { exact: true }).waitFor();
    await safeStorage(page);
    assert.deepEqual(errors, []);
    return id;
  } finally {
    await context?.close();
    await rm(profile, { recursive: true, force: true });
  }
}

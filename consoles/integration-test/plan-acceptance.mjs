import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright';

// Browser plugin not available. 正式 Chrome 页面经 HTTPS/Gateway/Nacos 访问真实服务。
export async function verifyPlan({
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
  quotaDefinitionId,
}) {
  const profile = await mkdtemp(path.join(tmpdir(), 'sf-plan-'));
  const base = `https://platform.${rootDomain}`;
  const project = process.env.SF_ACCEPTANCE_PROJECT;
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  assert.match(quotaDefinitionId, /^[0-9a-f-]{36}$/);
  const launch = () =>
    chromium.launchPersistentContext(profile, {
      channel: 'chrome',
      headless: true,
      ignoreHTTPSErrors: false,
      locale: 'zh-CN',
      viewport: { width: 1440, height: 960 },
    });
  const errors = [];
  let context;
  let id;
  try {
    context = await launch();
    let page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(base);
    await login(page, email, password, 'zh-CN');
    await page.goto(`${base}/plans`);
    await accessibility(page, '套餐');
    await page.getByRole('button', { name: '创建套餐', exact: true }).click();
    await page.waitForURL(`${base}/plans/new`);
    const createForm = page.getByRole('form', { name: '创建套餐', exact: true });
    await createForm.getByRole('textbox', { name: '编码', exact: false }).fill('browser-plan');
    await createForm.getByRole('textbox', { name: '名称', exact: false }).fill('Browser Plan');
    await createForm.getByRole('textbox', { name: 'max_users 上限', exact: false }).fill('0');
    await page.getByRole('button', { name: '创建套餐', exact: true }).click();
    await page.getByText(/编码需为/).waitFor();
    await createForm.getByRole('textbox', { name: 'max_users 上限', exact: false }).fill('1');
    let creates = 0;
    let createKey;
    let createStatus;
    await page.route('**/api/v1/platform/plans', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      creates++;
      createKey = route.request().headers()['idempotency-key'];
      const response = await route.fetch();
      createStatus = response.status();
      if (createStatus === 201) id = (await response.json()).id;
      await route.abort('failed');
    });
    try {
      await page.getByRole('button', { name: '创建套餐', exact: true }).dblclick();
      await page.getByText('操作结果待确认', { exact: true }).waitFor();
    } catch (error) {
      await capture(page, 'issue-174-create-failure');
      throw new Error(
        `Plan create recovery: requests=${creates}, status=${createStatus ?? 'none'}, pageErrors=${errors.length}`,
        { cause: error },
      );
    }
    assert.equal(creates, 1);
    assert.equal(createStatus, 201);
    assert.ok(id);
    assert.ok(createKey);
    await capture(page, 'issue-174-create-response-lost');
    await page.unroute('**/api/v1/platform/plans');
    await page.reload();
    await page.getByRole('button', { name: '读取操作记录', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: '创建 Plan' })
      .getByRole('button', { name: '查看套餐' })
      .click();
    await page.getByText(id, { exact: true }).waitFor();
    await accessibility(page, '套餐详情');
    let activations = 0;
    let activationStatus;
    await page.route('**/api/v1/platform/plans/*/activations', async (route) => {
      activations++;
      activationStatus = (await route.fetch()).status();
      await route.abort('failed');
    });
    await page.getByRole('button', { name: '激活套餐', exact: true }).dblclick();
    await page.getByText('操作结果待确认', { exact: true }).waitFor();
    assert.equal(activations, 1);
    assert.equal(activationStatus, 200);
    await capture(page, 'issue-174-activation-response-lost');
    await page.unroute('**/api/v1/platform/plans/*/activations');
    await page.reload();
    await page.getByText('已激活', { exact: true }).waitFor();
    await safeStorage(page);
    await capture(page, 'issue-174-active-detail');
    await page.goto(`${base}/plans`);
    await page.getByRole('textbox', { name: '编码', exact: true }).fill('browser-plan');
    await page.getByRole('combobox', { name: '状态', exact: true }).press('Enter');
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: '已激活' })
      .click();
    const query = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/v1/platform/plans' &&
        url.searchParams.get('status') === 'ACTIVE' &&
        url.searchParams.get('code') === 'browser-plan'
      );
    });
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.equal((await query).status(), 200);
    await page.getByRole('table').getByText('browser-plan', { exact: true }).waitFor();
    // 仅在隔离 Fresh 数据库模拟升级前记录；不通过新请求创建零额度。
    const legacyId = '019535d9-0000-7000-8000-000000000974';
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
        'entitlement_db',
        '-v',
        'ON_ERROR_STOP=1',
      ],
      {
        input: `INSERT INTO plans VALUES ('${legacyId}', 'legacy-zero', 'Legacy Zero', 'DRAFT', now(), now()); INSERT INTO plan_quotas VALUES ('${legacyId}', '${quotaDefinitionId}', 0);`,
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    );
    await selectLocale(page, 'English');
    await page.goto(`${base}/plans/${legacyId}`);
    await page
      .getByText(
        'Historical zero quota: ineligible for new grants; existing entitlements are unchanged.',
        { exact: true },
      )
      .waitFor();
    assert.equal(await page.getByRole('button', { name: 'Activate plan', exact: true }).count(), 0);
    await accessibility(page, 'Plan details');
    await capture(page, 'issue-174-legacy-english');
    await context.close();
    context = await launch();
    page = await context.newPage();
    await page.goto(`${base}/plans/${id}`);
    await page.getByText('Active', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(page, email, password, 'en-US');
    await page.goto(`${base}/plans/${id}`);
    await page.getByText('Active', { exact: true }).waitFor();
    await safeStorage(page);
    assert.deepEqual(errors, []);
  } finally {
    await context?.close();
    await rm(profile, { recursive: true, force: true });
  }
}

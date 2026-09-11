import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright';

// 所有成功响应来自真实服务；仅在服务端提交后丢弃一次创建响应。
export async function verifyTenantCreation({
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
}) {
  const profile = await mkdtemp(path.join(tmpdir(), 'sf-tenant-creation-'));
  let context;
  const launch = () =>
    chromium.launchPersistentContext(profile, {
      channel: 'chrome',
      headless: true,
      ignoreHTTPSErrors: false,
      locale: 'zh-CN',
      viewport: { width: 1440, height: 960 },
    });
  const base = `https://platform.${rootDomain}`;
  const name = `Tenant recovery ${randomUUID()}`;
  let creates = 0;
  let committedId;
  const errors = [];
  try {
    context = await launch();
    let page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(base);
    await accessibility(page, '登录 SaaS Forge');
    await login(page, email, password, 'zh-CN');
    await page.getByRole('link', { name: 'Tenant', exact: true }).click();
    await accessibility(page, 'Tenant');
    await page.getByRole('button', { name: '创建 Tenant', exact: true }).click();
    await accessibility(page, '创建 Tenant');
    await page
      .getByRole('form', { name: '创建 Tenant', exact: true })
      .getByRole('textbox', { name: '名称', exact: true })
      .fill('Unsaved draft');
    assert.equal(
      await page.getByRole('textbox', { name: '名称', exact: true }).inputValue(),
      'Unsaved draft',
    );
    await page.getByRole('link', { name: '首页', exact: true }).click();
    await page.getByRole('dialog', { name: '放弃未保存的修改？' }).waitFor();
    await page.getByRole('button', { name: '继续编辑', exact: true }).click();
    assert.equal(
      await page.getByRole('textbox', { name: '名称', exact: true }).inputValue(),
      'Unsaved draft',
    );
    await page.getByRole('textbox', { name: '名称', exact: true }).fill(name);
    await page.route('**/api/v1/platform/tenants', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      creates += 1;
      const response = await route.fetch();
      assert.equal(response.status(), 201);
      committedId = (await response.json()).id;
      await route.abort('failed');
    });
    await page.getByRole('button', { name: '创建 Tenant', exact: true }).dblclick();
    await page.getByText('创建结果待确认', { exact: true }).waitFor();
    assert.equal(creates, 1);
    await page.unroute('**/api/v1/platform/tenants');
    await capture(page, 'issue-172-response-lost');
    await safeStorage(page);
    await page.reload();
    await page.getByRole('textbox', { name: '名称', exact: true }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: '名称', exact: true }).inputValue(), '');
    await page.getByRole('button', { name: '读取创建记录', exact: true }).click();
    const attempt = page.getByRole('listitem').filter({ hasText: name });
    await attempt.getByRole('button', { name: '查看 Tenant' }).click();
    await accessibility(page, 'Tenant 详情');
    await page.getByText(committedId, { exact: true }).waitFor();
    await page.getByText('待初始化', { exact: true }).waitFor();
    await page.reload();
    await page.getByText(committedId, { exact: true }).waitFor();
    await capture(page, 'issue-172-authoritative-detail');
    await page.getByRole('button', { name: '返回 Tenant 列表', exact: true }).click();
    await page.getByRole('textbox', { name: '名称', exact: true }).fill(name);
    await page.getByRole('combobox', { name: '生命周期状态', exact: true }).press('Enter');
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: '待初始化' })
      .click();
    const filteredResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === '/api/v1/platform/tenants' &&
        url.searchParams.get('name') === name &&
        url.searchParams.get('status') === 'PENDING'
      );
    });
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.equal((await filteredResponse).status(), 200);
    await page
      .getByRole('table', { name: 'Tenant', exact: true })
      .getByText(name, { exact: true })
      .waitFor();
    await selectLocale(page, 'English');
    await page.getByRole('heading', { name: 'Tenants', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Read creation records', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: name })
      .getByRole('button', { name: 'View Tenant' })
      .click();
    await page.getByRole('heading', { name: 'Tenant details', exact: true }).waitFor();
    await page.getByText(committedId, { exact: true }).waitFor();
    await capture(page, 'issue-172-english-detail');
    await safeStorage(page);
    await context.close();
    context = await launch();
    page = await context.newPage();
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(`${base}/tenants`);
    await page.getByRole('heading', { name: 'Tenants', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Read creation records', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: name })
      .getByRole('button', { name: 'View Tenant' })
      .click();
    await page.getByText(committedId, { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(page, email, password, 'en-US');
    await page.goto(`${base}/tenants`);
    await page.getByRole('button', { name: 'Read creation records', exact: true }).click();
    await page
      .getByRole('listitem')
      .filter({ hasText: name })
      .getByRole('button', { name: 'View Tenant' })
      .click();
    await page.getByText(committedId, { exact: true }).waitFor();
    // 本次隔离项目的真实数据库拒绝写入；恢复后仍通过页面继续原操作。
    const project = process.env.SF_ACCEPTANCE_PROJECT;
    assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
    function allowTenantWrites(allow) {
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
          'tenant_access_db',
          '-v',
          'ON_ERROR_STOP=1',
        ],
        {
          input: allow
            ? 'GRANT INSERT ON tenants TO tenant_access_app;'
            : 'REVOKE INSERT ON tenants FROM tenant_access_app;',
          stdio: ['pipe', 'pipe', 'pipe'],
        },
      );
    }
    const rollbackName = `${name} rollback`;
    await page.goto(`${base}/tenants/new`);
    await accessibility(page, 'Create Tenant');
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(rollbackName);
    allowTenantWrites(false);
    try {
      await page.getByRole('button', { name: 'Create Tenant', exact: true }).click();
      await page.getByText('Creation result needs confirmation', { exact: true }).waitFor();
    } finally {
      allowTenantWrites(true);
    }
    await page.getByRole('button', { name: 'Read creation records', exact: true }).click();
    const recoverResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname.endsWith('/recovery') &&
        response.request().method() === 'POST',
    );
    await page
      .getByRole('listitem')
      .filter({ hasText: rollbackName })
      .getByRole('button', { name: 'Continue original creation' })
      .click();
    const recovered = await recoverResponse;
    assert.equal(recovered.status(), 200);
    assert.match(await recovered.request().headerValue('content-type'), /^application\/json/);
    await page.getByRole('heading', { name: 'Tenant details', exact: true }).waitFor();
    await page.getByText(rollbackName, { exact: true }).waitFor();
    await capture(page, 'issue-172-replayed-after-rollback');
    await page.goto(`${base}/tenants/new`);
    await accessibility(page, 'Create Tenant');
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(name);
    await page.getByRole('button', { name: 'Create Tenant', exact: true }).click();
    await page.getByRole('heading', { name: 'Tenant details', exact: true }).waitFor();
    await page.getByText(name, { exact: true }).waitFor();
    assert.notEqual(new URL(page.url()).pathname, `/tenants/${committedId}`);
    await safeStorage(page);
    assert.deepEqual(errors, []);
  } finally {
    await context?.close();
    await rm(profile, { recursive: true, force: true });
  }
}

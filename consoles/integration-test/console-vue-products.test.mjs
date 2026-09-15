/* global document, innerWidth */
import assert from 'node:assert/strict';
import test from 'node:test';
import { createConsoleTestServer } from './console-test-server.mjs';
import { chromium } from 'playwright';

const id = '019535d9-0000-7000-8000-000000000002';
const createdAt = '2026-09-11T00:00:00Z';
const tenant = {
  id,
  displayName: 'Acme',
  status: 'PENDING',
  expiresAt: null,
  createdAt,
  updatedAt: createdAt,
};
const definition = { id, code: 'max_users', status: 'ACTIVE', createdAt, updatedAt: createdAt };
const plan = {
  id,
  code: 'starter',
  displayName: 'Starter',
  status: 'DRAFT',
  quotaLimits: [{ quotaDefinitionId: id, limit: 1 }],
  createdAt,
  updatedAt: createdAt,
};
const pageOf = (items) => ({ items, nextCursor: null, hasMore: false });

// 正式产品路由与共享类型化 Client；HTTP 模拟证据不代表真实后端验收。
test('Vue business routes preserve operation recovery, form guards and authoritative reads', async (t) => {
  const server = await createConsoleTestServer('platform-console');
  await server.listen();
  const browser = await chromium.launch({ channel: process.env.SF_BROWSER_CHANNEL || undefined });
  t.after(async () => {
    await browser.close();
    await server.close();
  });
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  async function open(path, handle) {
    const context = await browser.newContext({
      locale: 'zh-CN',
      viewport: { width: 1440, height: 900 },
    });
    const errors = [];
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    page.on('pageerror', (e) => errors.push(e.message));
    await page.route('https://api.saas.forge.test/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      if (pathname.endsWith('/refresh'))
        return route.fulfill({
          json: {
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'isolated-product-regression',
            tokenType: 'Bearer',
            expiresIn: 120,
          },
        });
      if (await handle(route, pathname)) return;
      if (
        pathname.endsWith('/tenants') ||
        pathname.endsWith('/plan-operations') ||
        pathname.endsWith('/subscription-operations') ||
        pathname.endsWith('/tenant-creations')
      )
        return route.fulfill({ json: pageOf([]) });
      if (pathname.endsWith('/plans')) return route.fulfill({ json: pageOf([plan]) });
      if (pathname.endsWith('/quota-definitions'))
        return route.fulfill({ json: pageOf([definition]) });
      if (pathname.endsWith('/tenants/' + id)) return route.fulfill({ json: tenant });
      if (pathname.endsWith('/plans/' + id)) return route.fulfill({ json: plan });
      return route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        json: { code: 'UPSTREAM_UNAVAILABLE', status: 503, title: 'Unavailable' },
      });
    });
    await page.goto(base + path);
    return { page, context, errors };
  }
  await t.test(
    'lost Tenant response locks fields and recovers the original committed operation',
    async () => {
      let creates = 0,
        key = '';
      const { page, context, errors } = await open('/tenants/new', async (route, path) => {
        if (path.endsWith('/tenants') && route.request().method() === 'POST') {
          creates++;
          key = route.request().headers()['idempotency-key'];
          await route.abort('failed');
          return true;
        }
        if (path.endsWith('/tenant-creations')) {
          await route.fulfill({
            json: pageOf(
              key
                ? [
                    {
                      id,
                      displayName: 'Acme',
                      state: 'COMMITTED',
                      createdAt,
                      replayUntil: '2027-01-01T00:00:00Z',
                      canReplay: false,
                      tenantId: id,
                      idempotencyKey: key,
                    },
                  ]
                : [],
            ),
          });
          return true;
        }
      });
      try {
        const drawer = page.getByRole('dialog', { name: '创建 Tenant' });
        await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('Acme');
        await drawer.getByRole('button', { name: '创建 Tenant', exact: true }).click();
        await drawer.getByText('创建结果待确认', { exact: true }).waitFor();
        assert.equal(
          await drawer.getByRole('textbox', { name: '名称', exact: true }).isDisabled(),
          true,
        );
        await drawer.getByRole('button', { name: '读取创建记录', exact: true }).click();
        await drawer.getByRole('button', { name: '查看 Tenant', exact: true }).click();
        await page.getByRole('heading', { name: 'Tenant 详情', exact: true }).waitFor();
        await page.getByText('Acme', { exact: true }).waitFor();
        assert.equal(creates, 1);
        assert.deepEqual(errors, []);
      } finally {
        await context.close();
      }
    },
  );
  await t.test(
    'drawer escape protects dirty values and returning to the list restores focus',
    async () => {
      const { page, context, errors } = await open('/tenants', async () => false);
      try {
        const create = page.getByRole('button', { name: '创建 Tenant', exact: true });
        await create.click();
        const drawer = page.getByRole('dialog', { name: '创建 Tenant' });
        await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('Unsaved');
        await page.keyboard.press('Escape');
        await page.getByRole('button', { name: '继续编辑', exact: true }).click();
        await page
          .getByRole('button', { name: '放弃修改', exact: true })
          .waitFor({ state: 'hidden' });
        assert.equal(
          await drawer.getByRole('textbox', { name: '名称', exact: true }).inputValue(),
          'Unsaved',
        );
        await page.keyboard.press('Escape');
        await page.getByRole('button', { name: '放弃修改', exact: true }).click();
        await drawer.waitFor({ state: 'hidden' });
        assert.equal(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          true,
        );
        assert.deepEqual(errors, []);
      } finally {
        await context.close();
      }
    },
  );
  await t.test('Plan requires a positive grant and locks an uncertain creation', async () => {
    let creates = 0;
    const { page, context, errors } = await open('/plans/new', async (route, path) => {
      if (path.endsWith('/plans') && route.request().method() === 'POST') {
        creates++;
        assert.deepEqual(route.request().postDataJSON(), {
          code: 'starter',
          displayName: 'Starter',
          quotaLimits: [{ quotaDefinitionId: id, limit: 1 }],
        });
        await route.abort('failed');
        return true;
      }
    });
    try {
      const drawer = page.getByRole('dialog');
      await drawer.getByRole('textbox', { name: '编码', exact: true }).fill('starter');
      await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('Starter');
      await drawer.getByRole('textbox', { name: 'max_users 上限', exact: true }).fill('0');
      await drawer.getByRole('button', { name: '创建套餐', exact: true }).click();
      await drawer.getByText(/编码需为/).waitFor();
      assert.equal(creates, 0);
      await drawer.getByRole('textbox', { name: 'max_users 上限', exact: true }).fill('1');
      await drawer.getByRole('button', { name: '创建套餐', exact: true }).click();
      await drawer.getByText('操作结果待确认', { exact: true }).waitFor();
      assert.equal(
        await drawer.getByRole('button', { name: '创建套餐', exact: true }).isDisabled(),
        true,
      );
      assert.equal(creates, 1);
      assert.deepEqual(errors, []);
    } finally {
      await context.close();
    }
  });
  await t.test('Plan paginated guard failure prevents a new key', async () => {
    let pages = 0,
      creates = 0;
    const { page, context } = await open('/plans/new', async (route, path) => {
      if (path.endsWith('/plan-operations')) {
        pages++;
        await route.fulfill({ json: { items: [], hasMore: true, nextCursor: 'repeat' } });
        return true;
      }
      if (path.endsWith('/plans') && route.request().method() === 'POST') {
        creates++;
        await route.abort();
        return true;
      }
    });
    try {
      await page
        .getByRole('dialog')
        .getByText('INVALID_SERVICE_RESPONSE', { exact: true })
        .waitFor();
      assert.equal(
        await page
          .getByRole('dialog')
          .getByRole('button', { name: '创建套餐', exact: true })
          .isDisabled(),
        true,
      );
      assert.ok(pages >= 2);
      assert.equal(creates, 0);
    } finally {
      await context.close();
    }
  });
  await t.test(
    'Tenant panels preserve successful data and fail closed on an unavailable subscription',
    async () => {
      let initializationPosts = 0;
      const { page, context } = await open('/tenants/' + id, async (route, path) => {
        if (path.endsWith('/administrator-initialization')) {
          await route.fulfill({
            json: {
              tenantId: id,
              state: 'NOT_STARTED',
              canStart: true,
              canContinue: false,
              initialAdministratorMembershipId: null,
            },
          });
          return true;
        }
        if (path.endsWith('/administrator-initializations')) {
          initializationPosts++;
          await route.abort();
          return true;
        }
      });
      try {
        await page.getByText('Acme', { exact: true }).waitFor();
        const submit = page.getByRole('button', { name: '初始化管理员', exact: true });
        await submit.waitFor();
        assert.equal(await submit.isDisabled(), true);
        assert.equal(await page.getByText('尚无订阅', { exact: true }).count(), 0);
        assert.equal(initializationPosts, 0);
      } finally {
        await context.close();
      }
    },
  );
  await t.test(
    'a pending suspension exposes only original continuation, never resumption',
    async () => {
      const { page, context } = await open('/tenants/' + id, async (route, path) => {
        if (path.endsWith('/lifecycle')) {
          await route.fulfill({
            json: {
              tenantId: id,
              state: 'PENDING',
              action: 'SUSPEND',
              canContinue: true,
              canSuspend: false,
              canResume: false,
              canRecoverSuspension: false,
              operationId: id,
            },
          });
          return true;
        }
      });
      try {
        await page.getByRole('button', { name: '继续处理', exact: true }).waitFor();
        assert.equal(await page.getByRole('button', { name: '解除冻结', exact: true }).count(), 0);
      } finally {
        await context.close();
      }
    },
  );
  await t.test(
    'notification resend is independent and an unknown result cannot be resubmitted',
    async () => {
      let resends = 0,
        initializations = 0;
      const { page, context } = await open('/tenants/' + id, async (route, path) => {
        if (path.endsWith('/administrator-password-setups')) {
          resends++;
          await route.abort('failed');
          return true;
        }
        if (path.endsWith('/administrator-initializations')) {
          initializations++;
          await route.abort();
          return true;
        }
        if (path.endsWith('/administrator-password-setup')) {
          await route.fulfill({
            json: {
              tenantId: id,
              resendId: id,
              state: 'MAIL_SERVICE_ACCEPTED',
              operationState: resends ? 'UNKNOWN' : 'NONE',
              canResend: true,
              canContinue: false,
            },
          });
          return true;
        }
      });
      try {
        const resend = page.getByRole('button', { name: '重新发送通知', exact: true });
        await resend.click();
        await page.getByText('重发结果待确认', { exact: true }).waitFor();
        assert.equal(await resend.isDisabled(), true);
        assert.equal(resends, 1);
        assert.equal(initializations, 0);
      } finally {
        await context.close();
      }
    },
  );
  await t.test(
    'OAuth Secret is shown once and cleared without offering another creation',
    async () => {
      let creates = 0;
      const { page, context, errors } = await open('/oauth-clients/new', async (route, path) => {
        if (path.endsWith('/oauth-clients')) {
          if (route.request().method() === 'POST') {
            creates++;
            await route.fulfill({
              json: {
                clientId: id,
                displayName: 'Application',
                allowedScopes: ['runtime:read'],
                status: 'ACTIVE',
                createdAt,
                updatedAt: createdAt,
                clientSecret: 'test-only-one-time-value',
              },
            });
          } else await route.fulfill({ json: pageOf([]) });
          return true;
        }
      });
      try {
        const drawer = page.getByRole('dialog');
        await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('Application');
        await drawer.getByRole('button', { name: '创建接入凭据', exact: true }).click();
        await drawer.getByText('test-only-one-time-value', { exact: true }).waitFor();
        await drawer.getByRole('button', { name: '我已保存，关闭展示', exact: true }).click();
        assert.equal(await page.getByText('test-only-one-time-value', { exact: true }).count(), 0);
        assert.equal(
          await drawer.getByRole('button', { name: '创建接入凭据', exact: true }).count(),
          0,
        );
        assert.equal(creates, 1);
        assert.deepEqual(errors, []);
      } finally {
        await context.close();
      }
    },
  );
  await t.test('Quota recovery retains the original operation handle and key', async () => {
    let recovered = false,
      replays = 0,
      activations = 0;
    const originalKey = '019535d9-0000-7000-8000-000000000010';
    const operation = () => ({
      id,
      operation: 'ACTIVATE',
      state: recovered ? 'COMMITTED' : 'NOT_COMMITTED',
      createdAt,
      replayUntil: '2027-01-01T00:00:00Z',
      canReplay: !recovered,
      quotaDefinitionId: id,
      idempotencyKey: originalKey,
    });
    const { page, context, errors } = await open(
      '/quota-definitions/' + id,
      async (route, path) => {
        if (path.endsWith('/quota-definitions/' + id)) {
          await route.fulfill({ json: { ...definition, status: recovered ? 'ACTIVE' : 'DRAFT' } });
          return true;
        }
        if (path.endsWith('/quota-definition-operations')) {
          await route.fulfill({ json: pageOf([operation()]) });
          return true;
        }
        if (path.endsWith('/quota-definition-operations/' + id + '/recovery')) {
          replays++;
          assert.equal(route.request().headers()['idempotency-key'], originalKey);
          recovered = true;
          await route.fulfill({ json: operation() });
          return true;
        }
        if (path.endsWith('/activation')) {
          activations++;
          await route.abort();
          return true;
        }
      },
    );
    try {
      const drawer = page.getByRole('dialog');
      await drawer.getByRole('button', { name: '读取操作记录', exact: true }).click();
      await drawer.getByRole('button', { name: '继续原操作', exact: true }).click();
      await drawer.getByText('ACTIVE', { exact: true }).waitFor();
      assert.equal(replays, 1);
      assert.equal(activations, 0);
      assert.deepEqual(errors, []);
    } finally {
      await context.close();
    }
  });
  await t.test(
    'a pending Plan operation guards its code without blocking a different code',
    async () => {
      const { page, context } = await open('/plans/new', async (route, path) => {
        if (path.endsWith('/plan-operations')) {
          await route.fulfill({
            json: pageOf([
              {
                id,
                code: 'starter',
                operation: 'CREATE',
                state: 'NOT_COMMITTED',
                createdAt,
                replayUntil: createdAt,
                canReplay: false,
              },
            ]),
          });
          return true;
        }
      });
      try {
        const drawer = page.getByRole('dialog');
        const code = drawer.getByRole('textbox', { name: '编码', exact: true });
        await code.fill('starter');
        await drawer
          .getByText('已有操作待核查，请读取操作记录继续原操作或核查结果。', { exact: true })
          .waitFor();
        assert.equal(
          await drawer.getByRole('button', { name: '创建套餐', exact: true }).isDisabled(),
          true,
        );
        await code.fill('another-plan');
        await drawer.getByRole('button', { name: '创建套餐', exact: true }).waitFor();
        await page.waitForFunction(
          () => !document.querySelector('[role="dialog"] button[type="submit"]').disabled,
        );
      } finally {
        await context.close();
      }
    },
  );
  await t.test('a Plan without a positive quota cannot be activated', async () => {
    for (const quotaLimits of [[], [{ quotaDefinitionId: id, limit: 0 }]]) {
      const { page, context, errors } = await open('/plans/' + id, async (route, path) => {
        if (path.endsWith('/plans/' + id)) {
          await route.fulfill({ json: { ...plan, quotaLimits } });
          return true;
        }
      });
      try {
        const activate = page
          .getByRole('dialog')
          .getByRole('button', { name: '激活套餐', exact: true });
        await activate.waitFor();
        assert.equal(await activate.isDisabled(), true);
        assert.deepEqual(errors, []);
      } finally {
        await context.close();
      }
    }
  });
  await t.test('formal Tenant list and drawer remain usable at desktop widths', async () => {
    const { page, context, errors } = await open('/tenants', async (route, path) => {
      if (path.endsWith('/tenants')) {
        await route.fulfill({ json: pageOf([tenant]) });
        return true;
      }
    });
    try {
      await page.getByText('Acme', { exact: true }).waitFor();
      for (const width of [1440, 1024]) {
        await page.setViewportSize({ width, height: 900 });
        assert.equal(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          true,
        );
        await page.screenshot({
          path: `/tmp/vue-tenant-list-${width}.png`,
          fullPage: true,
          animations: 'disabled',
        });
      }
      await page.getByRole('button', { name: '创建 Tenant', exact: true }).click();
      await page.getByRole('dialog', { name: '创建 Tenant' }).waitFor();
      await page.evaluate(async () => {
        await Promise.all(
          document.getAnimations().map((animation) => animation.finished.catch(() => {})),
        );
      });
      await page.screenshot({
        path: '/tmp/vue-tenant-drawer.png',
        fullPage: true,
        animations: 'disabled',
      });
      assert.deepEqual(errors, []);
    } finally {
      await context.close();
    }
  });
});

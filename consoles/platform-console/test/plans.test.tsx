import { createRuntimeConfigBootstrap } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

const id = '019535d9-0000-7000-8000-000000000002';
const definition = {
  id,
  code: 'max_users',
  status: 'ACTIVE',
  createdAt: '2026-09-11T00:00:00Z',
  updatedAt: '2026-09-11T00:00:00Z',
};
const plan = {
  id,
  code: 'starter',
  displayName: 'Starter',
  status: 'DRAFT',
  quotaLimits: [{ quotaDefinitionId: id, limit: 1 }],
  createdAt: definition.createdAt,
  updatedAt: definition.updatedAt,
};
const pageOf = (items: unknown[]) => Response.json({ items, nextCursor: null, hasMore: false });
const session = () =>
  Response.json({
    contextState: 'ACCESS_TOKEN_ISSUED',
    accessToken: 'token',
    tokenType: 'Bearer',
    expiresIn: 120,
  });

it('validates new positive grants then recovers a lost create response with no second creation', async () => {
  window.history.replaceState(null, '', '/plans/new');
  let key = '';
  let creates = 0;
  mountPlan(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/refresh')) return session();
    if (path.endsWith('/quota-definitions')) return pageOf([definition]);
    if (path.endsWith('/plan-operations'))
      return pageOf(
        key
          ? [
              {
                id,
                operation: 'CREATE',
                state: 'COMMITTED',
                createdAt: definition.createdAt,
                replayUntil: '2026-09-12T00:00:00Z',
                canReplay: true,
                planId: id,
                idempotencyKey: key,
              },
            ]
          : [],
      );
    if (path.endsWith('/plans') && init?.method === 'POST') {
      creates++;
      expect(JSON.parse(typeof init.body === 'string' ? init.body : '')).toEqual({
        code: 'starter',
        displayName: 'Starter',
        quotaLimits: [{ quotaDefinitionId: id, limit: 1 }],
      });
      key = new Headers(init.headers).get('Idempotency-Key') ?? '';
      throw new TypeError('Response lost after commit');
    }
    if (path.endsWith(`/plans/${id}`)) return Response.json(plan);
    throw new Error(`Unexpected path ${path}`);
  });
  fireEvent.change(await screen.findByRole('textbox', { name: /编码/ }), {
    target: { value: 'starter' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: /名称/ }), { target: { value: 'Starter' } });
  fireEvent.change(screen.getByRole('textbox', { name: /max_users 上限/ }), {
    target: { value: '0' },
  });
  await screen.findByText('已引用 ACTIVE max_users 额度定义。');
  await waitFor(() => {
    expect(screen.getByRole('button', { name: '创建套餐' }).hasAttribute('disabled')).toBe(false);
  });
  fireEvent.click(screen.getByRole('button', { name: '创建套餐' }));
  expect(creates).toBe(0);
  expect(await screen.findByText(/编码需为/)).toBeTruthy();
  fireEvent.change(screen.getByRole('textbox', { name: /max_users 上限/ }), {
    target: { value: '1' },
  });
  fireEvent.click(screen.getByRole('button', { name: '创建套餐' }));
  await screen.findByText('操作结果待确认');
  fireEvent.click(screen.getByRole('button', { name: '读取操作记录' }));
  fireEvent.click(await screen.findByRole('button', { name: '查看套餐' }));
  expect(await screen.findByText(id)).toBeTruthy();
  expect(creates).toBe(1);
});

it.each(['DRAFT', 'ACTIVE'])(
  'reads historical zero %s without allowing activation in English',
  async (status) => {
    window.history.replaceState(null, '', `/plans/${id}`);
    mountPlan(async (input) => {
      await Promise.resolve();
      const path = new URL(input instanceof Request ? input.url : input).pathname;
      if (path.endsWith('/refresh')) return session();
      if (path.endsWith('/plan-operations')) return pageOf([]);
      return Response.json({ ...plan, status, quotaLimits: [{ quotaDefinitionId: id, limit: 0 }] });
    }, 'en-US');
    expect(
      await screen.findByText(
        'Historical zero quota: ineligible for new grants; existing entitlements are unchanged.',
      ),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Activate plan' })).toBeNull();
  },
);

it.each(['NOT_COMMITTED', 'PROCESSING', 'UNKNOWN'])(
  'blocks a new key after reload with a %s create',
  async (state) => {
    window.history.replaceState(null, '', '/plans/new');
    mountPlan(async (input) => {
      await Promise.resolve();
      const path = new URL(input instanceof Request ? input.url : input).pathname;
      if (path.endsWith('/refresh')) return session();
      if (path.endsWith('/quota-definitions')) return pageOf([definition]);
      return pageOf([
        {
          id,
          operation: 'CREATE',
          state,
          createdAt: definition.createdAt,
          replayUntil: definition.createdAt,
          canReplay: false,
        },
      ]);
    });
    await screen.findByText('已有操作待核查，请读取操作记录继续原操作或核查结果。');
    expect(screen.getByRole('button', { name: '创建套餐' }).hasAttribute('disabled')).toBe(true);
  },
);
function mountPlan(
  authenticationFetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
  locale: 'zh-CN' | 'en-US' = 'zh-CN',
) {
  render(
    <ConsoleLocaleProvider initialLocale={locale}>
      <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
        <PlatformConsoleApp
          bootstrap={createRuntimeConfigBootstrap(() =>
            Promise.resolve({
              ok: true,
              config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
            }),
          )}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </BrandApplicationProvider>
    </ConsoleLocaleProvider>,
  );
}

it('guards the original code but allows an explicitly different Plan after a rejected creation', async () => {
  window.history.replaceState(null, '', '/plans/new');
  mountPlan(async (input) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/refresh')) return session();
    if (path.endsWith('/quota-definitions')) return pageOf([definition]);
    return pageOf([
      {
        id,
        code: 'starter',
        operation: 'CREATE',
        state: 'NOT_COMMITTED',
        createdAt: definition.createdAt,
        replayUntil: definition.createdAt,
        canReplay: false,
      },
    ]);
  });
  const code = await screen.findByRole('textbox', { name: /编码/ });
  fireEvent.change(code, { target: { value: 'starter' } });
  await screen.findByText('已有操作待核查，请读取操作记录继续原操作或核查结果。');
  expect(screen.getByRole('button', { name: '创建套餐' }).hasAttribute('disabled')).toBe(true);
  fireEvent.change(code, { target: { value: 'another-plan' } });
  await waitFor(() => {
    expect(screen.getByRole('button', { name: '创建套餐' }).hasAttribute('disabled')).toBe(false);
  });
});

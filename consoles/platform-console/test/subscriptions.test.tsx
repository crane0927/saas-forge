import { createRuntimeConfigBootstrap } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PlatformConsoleApp } from '../src/app';

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
afterEach(cleanup);
const id = '019535d9-0000-7000-8000-000000000002';
const time = '2026-09-11T00:00:00Z';
const tenant = {
  id,
  displayName: 'Acme',
  status: 'PENDING',
  expiresAt: null,
  createdAt: time,
  updatedAt: time,
};
const subscription = {
  id,
  tenantId: id,
  planId: id,
  status: 'ACTIVE',
  endsAt: null,
  createdAt: time,
};
const pageOf = (items: unknown[]) => Response.json({ items, nextCursor: null, hasMore: false });
function mount(
  fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
  locale: 'zh-CN' | 'en-US' = 'zh-CN',
) {
  window.history.replaceState(null, '', `/tenants/${id}`);
  return render(
    <ConsoleLocaleProvider initialLocale={locale}>
      <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
        <PlatformConsoleApp
          bootstrap={createRuntimeConfigBootstrap(() =>
            Promise.resolve({
              ok: true,
              config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
            }),
          )}
          authenticationFetch={fetch}
          realm={{}}
        />
      </BrandApplicationProvider>
    </ConsoleLocaleProvider>,
  );
}
function basic(path: string) {
  if (path.endsWith('/refresh'))
    return Response.json({
      contextState: 'ACCESS_TOKEN_ISSUED',
      accessToken: 'token',
      tokenType: 'Bearer',
      expiresIn: 120,
    });
  if (path.endsWith(`/tenants/${id}`)) return Response.json(tenant);
  if (path.endsWith('/plans'))
    return pageOf([
      {
        id,
        code: 'starter',
        displayName: 'Starter',
        status: 'ACTIVE',
        quotaLimits: [{ quotaDefinitionId: id, limit: 2 }],
        createdAt: time,
        updatedAt: time,
      },
    ]);
}
it('recovers a lost Subscription create response and reads authoritative usage after refresh', async () => {
  let creates = 0;
  let key = '';
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/subscriptions') && init?.method === 'POST') {
      creates++;
      key = new Headers(init.headers).get('Idempotency-Key') ?? '';
      throw new TypeError('Committed response lost');
    }
    if (path.endsWith('/subscription'))
      return Response.json({
        observedAt: time,
        subscription: key ? subscription : null,
        effective: Boolean(key),
        maxUsersLimit: key ? 2 : null,
        maxUsersUsed: key ? 1 : null,
      });
    if (path.endsWith('/subscription-operations'))
      return pageOf(
        key
          ? [
              {
                id,
                tenantId: id,
                subscriptionId: id,
                state: 'COMMITTED',
                createdAt: time,
                replayUntil: time,
                canReplay: false,
              },
            ]
          : [],
      );
    return basic(path) ?? new Response(null, { status: 404 });
  });
  const select = await screen.findByRole('combobox', { name: 'Plan' });
  await waitFor(() => {
    expect(select.hasAttribute('disabled')).toBe(false);
  });
  fireEvent.mouseDown(select);
  fireEvent.click(await screen.findByText('Starter (starter) — 2'));
  const create = screen.getByRole('button', { name: '创建首个 Subscription' });
  await waitFor(() => {
    expect(create.hasAttribute('disabled')).toBe(false);
  });
  fireEvent.click(create);
  await screen.findByText('订阅创建结果待确认');
  await screen.findByText('max_users 已用量');
  expect(screen.getByText('1')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '读取操作记录' }));
  fireEvent.click(await screen.findByRole('button', { name: '查看订阅' }));
  await waitFor(() => {
    expect(screen.queryByText('订阅创建结果待确认')).toBeNull();
  });
  expect(creates).toBe(1);
});
it('retains confirmed Tenant information during an Entitlement failure and retries the failed region', async () => {
  let failed = true;
  mount(async (input) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/subscription'))
      return failed
        ? Response.json({ code: 'DEPENDENCY_UNAVAILABLE' }, { status: 503 })
        : Response.json({
            observedAt: time,
            subscription,
            effective: false,
            maxUsersLimit: 2,
            maxUsersUsed: 1,
          });
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    return basic(path) ?? new Response(null, { status: 404 });
  }, 'en-US');
  await screen.findByText('Entitlements unavailable');
  expect(screen.getByText('Acme')).toBeTruthy();
  expect(screen.queryByText('No Subscription')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Create initial Subscription' })).toBeNull();
  failed = false;
  fireEvent.click(screen.getByRole('button', { name: 'Retry entitlement read' }));
  await screen.findByText('Expired');
  expect(screen.getByText('ACTIVE')).toBeTruthy();
  expect(screen.getByText('1')).toBeTruthy();
});

it('keeps creation disabled until the current Plan refresh finishes', async () => {
  let delayPlans = false;
  let releasePlans: ((response: Response) => void) | undefined;
  let reads = 0;
  mount(async (input) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/subscription')) {
      reads++;
      return Response.json({
        observedAt: time,
        subscription: null,
        effective: false,
        maxUsersLimit: null,
        maxUsersUsed: null,
      });
    }
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    if (path.endsWith('/plans') && delayPlans)
      return new Promise<Response>((resolve) => {
        releasePlans = resolve;
      });
    return basic(path) ?? new Response(null, { status: 404 });
  });
  const select = await screen.findByRole('combobox', { name: 'Plan' });
  await waitFor(() => {
    expect(select.hasAttribute('disabled')).toBe(false);
  });
  fireEvent.mouseDown(select);
  fireEvent.click(await screen.findByText('Starter (starter) — 2'));
  const create = screen.getByRole('button', { name: '创建首个 Subscription' });
  await waitFor(() => {
    expect(create.hasAttribute('disabled')).toBe(false);
  });
  delayPlans = true;
  fireEvent.click(screen.getByRole('button', { name: '重试权益读取' }));
  await waitFor(() => {
    expect(reads).toBe(2);
  });
  expect(create.hasAttribute('disabled')).toBe(true);
  releasePlans?.(Response.json({ code: 'UNAVAILABLE' }, { status: 503 }));
  await screen.findByText('可选 Plan 暂时无法读取');
  expect(create.hasAttribute('disabled')).toBe(true);
});

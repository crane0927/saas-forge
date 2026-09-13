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
it('initializes once, treats a lost response as unknown, and independently re-reads Tenant and Quota', async () => {
  let initialized = false;
  let creates = 0;
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/administrator-initializations') && init?.method === 'POST') {
      creates++;
      initialized = true;
      throw new TypeError('Response lost');
    }
    if (path.endsWith(`/tenants/${id}`))
      return Response.json({ ...tenant, status: initialized ? 'ACTIVE' : 'PENDING' });
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
        initializationId: initialized ? id : undefined,
        state: initialized ? 'SUCCEEDED' : 'NOT_STARTED',
        canStart: !initialized,
        canContinue: false,
        initialAdministratorMembershipId: initialized ? id : null,
      });
    if (path.endsWith('/subscription'))
      return Response.json({
        observedAt: time,
        subscription,
        effective: true,
        maxUsersLimit: 2,
        maxUsersUsed: initialized ? 1 : 0,
      });
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    return basic(path) ?? Response.json({});
  });
  const email = await screen.findByRole('textbox', { name: /管理员邮箱/ });
  fireEvent.change(email, { target: { value: 'administrator@example.test' } });
  const submit = screen.getByRole('button', { name: '初始化管理员' });
  await waitFor(() => {
    expect(submit.hasAttribute('disabled')).toBe(false);
  });
  fireEvent.click(submit);
  fireEvent.click(submit);
  expect(await screen.findByText('初始化结果待确认')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '重新读取初始化进度' }));
  expect(await screen.findByText('初始化已完成')).toBeTruthy();
  expect(screen.getByText('初始管理员历史 Membership')).toBeTruthy();
  await waitFor(() => {
    expect(screen.getByText('1')).toBeTruthy();
  });
  expect(creates).toBe(1);
  expect(screen.queryByText('已送达')).not.toBeTruthy();
});

it('keeps confirmed Membership and Quota visible when only the Tenant refresh fails', async () => {
  let tenantFailed = false;
  mount(async (input) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith(`/tenants/${id}`)) {
      if (tenantFailed) throw new TypeError('Tenant unavailable');
      return Response.json({ ...tenant, status: 'ACTIVE' });
    }
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
        initializationId: id,
        state: 'SUCCEEDED',
        canStart: false,
        canContinue: false,
        initialAdministratorMembershipId: id,
      });
    if (path.endsWith('/subscription'))
      return Response.json({
        observedAt: time,
        subscription,
        effective: true,
        maxUsersLimit: 2,
        maxUsersUsed: 1,
      });
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    return basic(path) ?? Response.json({});
  });
  await screen.findByText('初始化已完成');
  tenantFailed = true;
  fireEvent.click(screen.getByRole('button', { name: '重新读取初始化进度' }));
  await screen.findByText('暂时无法读取');
  expect(screen.getByText('初始管理员历史 Membership')).toBeTruthy();
  expect(screen.getByText('max_users 已用量')).toBeTruthy();
  expect(screen.getByText('Acme')).toBeTruthy();
});

it('shows other actors recovery progress in English without a takeover action', async () => {
  mount(async (input) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
        initializationId: id,
        state: 'RECOVERY_REQUIRED',
        canStart: false,
        canContinue: false,
        initialAdministratorMembershipId: null,
      });
    if (path.endsWith('/subscription')) throw new TypeError('Entitlement unavailable');
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    return basic(path) ?? Response.json({});
  }, 'en-US');
  await screen.findByText(
    'Automatic recovery paused. The original initiator may continue this attempt.',
  );
  expect(screen.queryByRole('button', { name: 'Continue original initialization' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Initialize administrator' })).toBeNull();
  expect(screen.getByText('Acme')).toBeTruthy();
});

it('does not allow a new Key when an unknown submitted request has no visible root yet', async () => {
  let creates = 0;
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/administrator-initializations') && init?.method === 'POST') {
      creates++;
      throw new TypeError('Unknown delivery');
    }
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
        state: 'NOT_STARTED',
        canStart: true,
        canContinue: false,
        initialAdministratorMembershipId: null,
      });
    if (path.endsWith('/subscription'))
      return Response.json({
        observedAt: time,
        subscription,
        effective: true,
        maxUsersLimit: 2,
        maxUsersUsed: 0,
      });
    if (path.endsWith('/subscription-operations')) return pageOf([]);
    return basic(path) ?? Response.json({});
  });
  fireEvent.change(await screen.findByRole('textbox', { name: /管理员邮箱/ }), {
    target: { value: 'owner@example.test' },
  });
  const submit = screen.getByRole('button', { name: '初始化管理员' });
  await waitFor(() => {
    expect(submit.hasAttribute('disabled')).toBe(false);
  });
  fireEvent.click(submit);
  await screen.findByText('初始化结果待确认');
  fireEvent.click(screen.getByRole('button', { name: '重新读取初始化进度' }));
  await waitFor(() => {
    expect(
      screen.getByRole('button', { name: '重新读取初始化进度' }).hasAttribute('disabled'),
    ).toBe(false);
  });
  expect(submit.hasAttribute('disabled')).toBe(true);
  expect(creates).toBe(1);
});

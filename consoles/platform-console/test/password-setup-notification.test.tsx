import { createRuntimeConfigBootstrap } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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
it('resends notification independently and re-reads mail service acceptance without initializing again', async () => {
  let sent = false;
  let resends = 0;
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/administrator-password-setups') && init?.method === 'POST') {
      resends++;
      sent = true;
      return new Response(null, { status: 204 });
    }
    if (path.endsWith('/administrator-password-setup'))
      return Response.json({
        tenantId: id,
        state: sent ? 'MAIL_SERVICE_ACCEPTED' : 'ACTION_REQUIRED',
        operationState: sent ? 'COMPLETED' : 'NONE',
        canResend: true,
        canContinue: false,
      });
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
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
    if (path.endsWith(`/tenants/${id}`)) return Response.json({ ...tenant, status: 'ACTIVE' });
    if (path.endsWith('/administrator-initializations'))
      throw new Error('Must never initialize again');
    return basic(path) ?? Response.json({});
  });
  const resend = await screen.findByRole('button', { name: '重新发送通知' });
  fireEvent.click(resend);
  fireEvent.click(resend);
  await screen.findByText('已交付邮件服务');
  expect(resends).toBe(1);
  expect(screen.getByText('邮件服务接受不代表收件箱送达。')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '初始化管理员' })).toBeNull();
});

it('keeps an unknown resend disabled when refresh only returns the previous success, in English', async () => {
  let resends = 0;
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/administrator-password-setups') && init?.method === 'POST') {
      resends++;
      throw new TypeError('Response lost');
    }
    if (path.endsWith('/administrator-password-setup'))
      return Response.json({
        tenantId: id,
        resendId: id,
        state: 'MAIL_SERVICE_ACCEPTED',
        operationState: resends > 0 ? 'UNKNOWN' : 'NONE',
        canResend: true,
        canContinue: false,
      });
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
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
    if (path.endsWith(`/tenants/${id}`)) return Response.json({ ...tenant, status: 'ACTIVE' });
    return basic(path) ?? Response.json({});
  }, 'en-US');
  fireEvent.click(await screen.findByRole('button', { name: 'Resend notification' }));
  await screen.findByText('Resend result awaiting confirmation');
  expect(screen.getByRole('button', { name: 'Resend notification' }).hasAttribute('disabled')).toBe(
    true,
  );
  expect(resends).toBe(1);
  expect(screen.getByText('Accepted by mail service')).toBeTruthy();
});

it('confirms a completed original recovery even when its response is lost and the resend ID stays the same', async () => {
  let recovered = false;
  mount(async (input, init) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/recovery') && init?.method === 'POST') {
      recovered = true;
      throw new TypeError('Lost recovery response');
    }
    if (path.endsWith('/administrator-password-setup'))
      return Response.json({
        tenantId: id,
        resendId: id,
        state: recovered ? 'MAIL_SERVICE_ACCEPTED' : 'ACTION_REQUIRED',
        operationState: recovered ? 'COMPLETED' : 'PENDING',
        canResend: recovered,
        canContinue: !recovered,
      });
    if (path.endsWith('/administrator-initialization'))
      return Response.json({
        tenantId: id,
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
    if (path.endsWith(`/tenants/${id}`)) return Response.json({ ...tenant, status: 'ACTIVE' });
    return basic(path) ?? Response.json({});
  });
  fireEvent.click(await screen.findByRole('button', { name: '继续原重发' }));
  await screen.findByText('已交付邮件服务');
  expect(screen.queryByText('重发结果待确认')).toBeNull();
  expect(screen.getByRole('button', { name: '重新发送通知' }).hasAttribute('disabled')).toBe(false);
});

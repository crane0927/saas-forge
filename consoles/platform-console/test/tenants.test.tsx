import { createRuntimeConfigBootstrap } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

it('recovers a committed Tenant after a lost create response without creating again', async () => {
  window.history.replaceState(null, '', '/tenants/new');
  const id = '019535d9-0000-7000-8000-000000000002';
  let key = '';
  let creates = 0;
  const tenant = {
    id,
    displayName: 'Acme',
    status: 'PENDING',
    expiresAt: null,
    createdAt: '2026-09-11T00:00:00.000Z',
    updatedAt: '2026-09-11T00:00:00.000Z',
  };
  const authenticationFetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/refresh'))
      return Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 120,
      });
    if (path.endsWith('/tenants') && init?.method === 'POST') {
      creates += 1;
      key = new Headers(init.headers).get('Idempotency-Key') ?? '';
      throw new TypeError('Response lost after commit');
    }
    if (path.endsWith('/tenant-creations'))
      return Response.json({
        items: [
          {
            id,
            displayName: 'Acme',
            state: 'COMMITTED',
            createdAt: tenant.createdAt,
            replayUntil: '2026-09-12T00:00:00.000Z',
            canReplay: true,
            tenantId: id,
            idempotencyKey: key,
          },
        ],
        nextCursor: null,
        hasMore: false,
      });
    if (path.endsWith(`/tenants/${id}`)) return Response.json(tenant);
    throw new Error(`Unexpected request: ${path}`);
  };
  render(
    <ConsoleLocaleProvider initialLocale="zh-CN">
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
  fireEvent.change(await screen.findByRole('textbox', { name: '名称' }), {
    target: { value: 'Acme' },
  });
  fireEvent.click(screen.getByRole('button', { name: '创建 Tenant' }));
  expect(await screen.findByText('创建结果待确认')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '读取创建记录' }));
  fireEvent.click(await screen.findByRole('button', { name: '查看 Tenant' }));
  expect(await screen.findByRole('heading', { name: 'Tenant 详情' })).toBeTruthy();
  expect(await screen.findByText('Acme')).toBeTruthy();
  expect(creates).toBe(1);
});

it('asks before signing out of a modified form and can continue editing', async () => {
  window.history.replaceState(null, '', '/tenants/new');
  let logouts = 0;
  const authenticationFetch = (input: RequestInfo | URL) => {
    const pathname = new URL(input instanceof Request ? input.url : input).pathname;
    if (pathname.endsWith('/logout')) {
      logouts += 1;
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 120,
      }),
    );
  };
  render(
    <ConsoleLocaleProvider initialLocale="zh-CN">
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
  fireEvent.change(await screen.findByRole('textbox', { name: '名称' }), {
    target: { value: 'Unsaved' },
  });
  fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
  expect(await screen.findByRole('dialog', { name: '放弃未保存的修改？' })).toBeTruthy();
  expect(logouts).toBe(0);
  fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(screen.getByRole<HTMLInputElement>('textbox', { name: '名称' }).value).toBe('Unsaved');
  fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
  fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
  expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
  expect(logouts).toBe(1);
});

import { createRuntimeConfigBootstrap } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

it('recovers a committed Quota Definition after a lost create response without creating again', async () => {
  window.history.replaceState(null, '', '/quota-definitions/new');
  const id = '019535d9-0000-7000-8000-000000000002';
  let key = '';
  let creates = 0;
  const tenant = {
    id,
    code: 'max_users',
    status: 'DRAFT',
    expiresAt: null,
    createdAt: '2026-09-11T00:00:00.000Z',
    updatedAt: '2026-09-11T00:00:00.000Z',
  };
  const authenticationFetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    await Promise.resolve();
    await Promise.resolve();
    const path = new URL(input instanceof Request ? input.url : input).pathname;
    if (path.endsWith('/refresh'))
      return Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 120,
      });
    if (path.endsWith('/quota-definitions') && init?.method !== 'POST')
      return Response.json({ items: [], nextCursor: null, hasMore: false });
    if (path.endsWith('/quota-definitions') && init?.method === 'POST') {
      creates += 1;
      key = new Headers(init.headers).get('Idempotency-Key') ?? '';
      throw new TypeError('Response lost after commit');
    }
    if (path.endsWith('/quota-definition-operations'))
      return Response.json({
        items: key
          ? [
              {
                id,
                operation: 'CREATE',
                state: 'COMMITTED',
                createdAt: tenant.createdAt,
                replayUntil: '2026-09-12T00:00:00.000Z',
                canReplay: true,
                quotaDefinitionId: id,
                idempotencyKey: key,
              },
            ]
          : [],
        nextCursor: null,
        hasMore: false,
      });
    if (path.endsWith(`/quota-definitions/${id}`)) return Response.json(tenant);
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
  fireEvent.click(await screen.findByRole('button', { name: '创建 max_users' }));
  expect(await screen.findByText('操作结果待确认')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '读取操作记录' }));
  fireEvent.click(await screen.findByRole('button', { name: '查看额度定义' }));
  expect(await screen.findByRole('heading', { name: '额度定义详情' })).toBeTruthy();
  expect(await screen.findByText('max_users')).toBeTruthy();
  expect(creates).toBe(1);
});

it.each([403, 503])(
  'does not offer creation when the definition lookup returns %s',
  async (status) => {
    window.history.replaceState(null, '', '/quota-definitions/new');
    const authenticationFetch = async (input: RequestInfo | URL) => {
      await Promise.resolve();
      const path = new URL(input instanceof Request ? input.url : input).pathname;
      if (path.endsWith('/refresh'))
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'token',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      return Response.json(
        { code: status === 403 ? 'PLATFORM_AUTHORIZATION_DENIED' : 'SERVICE_UNAVAILABLE', status },
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      );
    };
    mountQuota(authenticationFetch);
    expect(await screen.findByText('读取失败')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '创建 max_users' })).toBeNull();
    expect(screen.queryByRole('button', { name: '复用 max_users' })).toBeNull();
  },
);

it('reuses an existing definition in English without issuing a creation', async () => {
  window.history.replaceState(null, '', '/quota-definitions/new');
  const id = '019535d9-0000-7000-8000-000000000002';
  const definition = {
    id,
    code: 'max_users',
    status: 'ACTIVE',
    createdAt: '2026-09-11T00:00:00Z',
    updatedAt: '2026-09-11T00:00:00Z',
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
    expect(init?.method).toBe('GET');
    if (path.endsWith('/quota-definition-operations'))
      return Response.json({ items: [], nextCursor: null, hasMore: false });
    return Response.json(
      path.endsWith(id) ? definition : { items: [definition], nextCursor: null, hasMore: false },
    );
  };
  mountQuota(authenticationFetch, 'en-US');
  fireEvent.click(await screen.findByRole('button', { name: 'Reuse max_users' }));
  expect(await screen.findByText('Active')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Create max_users' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Activate max_users' })).toBeNull();
});

function mountQuota(
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

it.each(['NOT_COMMITTED', 'PROCESSING', 'UNKNOWN'])(
  'blocks a new Key after refresh with an unresolved %s creation',
  async (state) => {
    window.history.replaceState(null, '', '/quota-definitions/new');
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
      expect(init?.method).toBe('GET');
      return Response.json({
        items: path.endsWith('/quota-definition-operations')
          ? [
              {
                id: '019535d9-0000-7000-8000-000000000003',
                operation: 'CREATE',
                state,
                createdAt: '2026-09-11T00:00:00Z',
                replayUntil: '2026-09-12T00:00:00Z',
                canReplay: false,
              },
            ]
          : [],
        nextCursor: null,
        hasMore: false,
      });
    };
    mountQuota(authenticationFetch);
    expect(
      await screen.findByText('已有操作待核查，请读取操作记录继续原操作或核查结果。'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: '创建 max_users' })).toBeNull();
  },
);

it('continues a rolled-back activation with the original Key and clears the pending warning', async () => {
  const id = '019535d9-0000-7000-8000-000000000002';
  const operationId = '019535d9-0000-7000-8000-000000000003';
  window.history.replaceState(null, '', `/quota-definitions/${id}`);
  let key = '';
  let active = false;
  let activations = 0;
  const operation = () => ({
    id: operationId,
    operation: 'ACTIVATE',
    state: active ? 'COMMITTED' : 'NOT_COMMITTED',
    quotaDefinitionId: id,
    createdAt: '2026-09-11T00:00:00Z',
    replayUntil: '2026-09-12T00:00:00Z',
    canReplay: true,
    idempotencyKey: key,
  });
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
    if (path.endsWith('/activations')) {
      activations += 1;
      key = new Headers(init?.headers).get('Idempotency-Key') ?? '';
      throw new TypeError('Response lost');
    }
    if (path.endsWith('/recovery')) {
      expect(new Headers(init?.headers).get('Idempotency-Key')).toBe(key);
      active = true;
      return Response.json(operation());
    }
    if (path.endsWith('/quota-definition-operations'))
      return Response.json({ items: key ? [operation()] : [], nextCursor: null, hasMore: false });
    return Response.json({
      id,
      code: 'max_users',
      status: active ? 'ACTIVE' : 'DRAFT',
      createdAt: '2026-09-11T00:00:00Z',
      updatedAt: '2026-09-11T00:00:00Z',
    });
  };
  mountQuota(authenticationFetch);
  fireEvent.click(await screen.findByRole('button', { name: '激活 max_users' }));
  expect(await screen.findByText('操作结果待确认')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '读取操作记录' }));
  fireEvent.click(await screen.findByRole('button', { name: '继续原操作' }));
  expect(await screen.findByText('已激活')).toBeTruthy();
  expect(screen.queryByText('操作结果待确认')).toBeNull();
  expect(activations).toBe(1);
});

it.each(['NOT_COMMITTED', 'PROCESSING', 'UNKNOWN'])(
  'blocks a fresh activation Key after refresh when %s',
  async (state) => {
    const id = '019535d9-0000-7000-8000-000000000002';
    window.history.replaceState(null, '', `/quota-definitions/${id}`);
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
      expect(init?.method).toBe('GET');
      if (path.endsWith('/quota-definition-operations'))
        return Response.json({
          items: [
            {
              id: '019535d9-0000-7000-8000-000000000003',
              operation: 'ACTIVATE',
              state,
              quotaDefinitionId: id,
              createdAt: '2026-09-11T00:00:00Z',
              replayUntil: '2026-09-12T00:00:00Z',
              canReplay: false,
            },
          ],
          nextCursor: null,
          hasMore: false,
        });
      return Response.json({
        id,
        code: 'max_users',
        status: 'DRAFT',
        createdAt: '2026-09-11T00:00:00Z',
        updatedAt: '2026-09-11T00:00:00Z',
      });
    };
    mountQuota(authenticationFetch);
    expect(
      await screen.findByText('已有操作待核查，请读取操作记录继续原操作或核查结果。'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: '激活 max_users' })).toBeNull();
  },
);

import { createRuntimeConfigBootstrap, type RuntimeConfigResult } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TenantConsoleShellApp } from '../src/app';

afterEach(cleanup);

describe('TenantConsoleShellApp', () => {
  it('creates the fixed Tenant authentication path and enters the only accessible Membership', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'tenant-memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(loader)}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
    expect(jsonRequestBody(authenticationFetch.mock.calls[1])).toEqual({
      email: 'member@example.test',
      password: 'secret',
      contextType: 'TENANT',
    });
  });

  it('keeps a user without an Accessible Membership anonymous and provides a safe next step', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(403, 'ACCESS_CONTEXT_UNAVAILABLE'));

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(
      await screen.findByText('当前 Identity 没有可进入的 Tenant，请联系 Tenant 管理员。'),
    ).toBeTruthy();
    expect(screen.getByText('错误代码：ACCESS_CONTEXT_UNAVAILABLE')).toBeTruthy();
    expect(screen.getByRole('form', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('keeps an over-limit Membership response anonymous and provides a safe next step', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(409, 'ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED'));

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(
      await screen.findByText('Accessible Membership 数量超过当前选择上限，请联系平台管理员。'),
    ).toBeTruthy();
    expect(screen.getByText('错误代码：ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED')).toBeTruthy();
    expect(screen.getByRole('form', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('requires an explicit Tenant slot logout instead of replacing an active Tenant Family', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(409, 'SESSION_SLOT_ALREADY_ACTIVE'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('当前 Tenant 会话槽位已有活动会话。')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '先登出当前 Tenant 会话' }));

    expect(await screen.findByRole('heading', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(authenticationFetch.mock.calls[2]?.[0]).toBe(
      'https://api.example.test/api/v1/auth/logout',
    );
    expect(jsonRequestBody(authenticationFetch.mock.calls[2])).toEqual({ sessionSlot: 'TENANT' });
  });

  it('recovers and logs out only the Tenant session slot', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
          authenticationFetch={authenticationFetch}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(jsonRequestBody(authenticationFetch.mock.calls[0])).toEqual({ sessionSlot: 'TENANT' });

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));

    expect(await screen.findByRole('heading', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(jsonRequestBody(authenticationFetch.mock.calls[1])).toEqual({ sessionSlot: 'TENANT' });
  });

  it('mounts the Tenant route tree only after runtime configuration succeeds', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(loader)}
          authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('heading', { name: '正在启动 Tenant Console' })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
  });

  it('shows a stable configuration failure and retries only after user action', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_UNAVAILABLE' } })
      .mockResolvedValueOnce(success());

    render(
      <DesignSystemProvider>
        <TenantConsoleShellApp
          bootstrap={createRuntimeConfigBootstrap(loader)}
          authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
          realm={{}}
        />
      </DesignSystemProvider>,
    );

    expect(await screen.findByText('CONFIG_UNAVAILABLE')).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('heading', { name: '登录 Tenant Console' })).toBeTruthy();
    expect(loader).toHaveBeenCalledTimes(2);
  });
});

function success(): RuntimeConfigResult {
  return {
    ok: true,
    config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
  };
}

function jsonRequestBody(
  call: readonly [input: RequestInfo | URL, init?: RequestInit] | undefined,
): unknown {
  const body = call?.[1]?.body;
  if (typeof body !== 'string') {
    throw new Error('expected a JSON request body');
  }
  return JSON.parse(body) as unknown;
}

function problemResponse(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      type: `urn:saasforge:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: 'not exposed',
      status,
      code,
      detail: 'raw service detail',
      traceId: '0123456789abcdef0123456789abcdef',
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

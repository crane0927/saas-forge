import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render as renderReact, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router';

import {
  AuthenticationRootErrorBoundary,
  AuthenticationShell,
  ConsoleLocaleProvider,
  useConsoleLocale,
} from '../src';

afterEach(cleanup);

function render(ui: ReactNode) {
  return renderReact(<ConsoleLocaleProvider initialLocale="zh-CN">{ui}</ConsoleLocaleProvider>);
}

describe('AuthenticationShell', () => {
  it('shows server-ordered Tenant Memberships and opens protected routes only after selection', async () => {
    const firstMembershipId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071';
    const secondMembershipId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'CONTEXT_SELECTION_REQUIRED',
          memberships: [
            {
              membershipId: firstMembershipId,
              tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
              tenantDisplayName: '北辰科技',
            },
            {
              membershipId: secondMembershipId,
              tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6075',
              tenantDisplayName: '云帆数据',
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'selected-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'TENANT', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Tenant Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[{ path: '/', label: '工作台', element: <h1>Tenant 工作台</h1> }]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    const heading = await screen.findByRole('heading', { name: '选择 Tenant' });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    const candidates = screen.getAllByRole('listitem');
    expect(candidates.map((candidate) => candidate.textContent)).toEqual([
      '北辰科技进入 北辰科技',
      '云帆数据进入 云帆数据',
    ]);
    expect(screen.queryByRole('navigation')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '进入 云帆数据' }));

    expect(await screen.findByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(jsonRequestBody(fetch.mock.calls[2])).toEqual({ membershipId: secondMembershipId });
  });

  it('shows the Platform login after cold-start recovery finds no valid session', async () => {
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      {
        realm: {},
        intent: 'PLATFORM',
        fetch: () => Promise.resolve(new Response(null, { status: 401 })),
      },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    const heading = await screen.findByRole('heading', { name: '登录 Platform Console' });
    expect(heading).toBeTruthy();
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    expect(screen.getByLabelText(/^邮箱/)).toBeTruthy();
    expect(screen.getByLabelText(/^密码/)).toBeTruthy();
  });

  it('switches shared authentication text without resetting inputs, focus, or an in-flight login', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockImplementationOnce(() => new Promise<Response>(() => undefined));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[]}
          />
          <LocaleSwitcher />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    const email = await screen.findByLabelText(/^邮箱/);
    const password = screen.getByLabelText(/^密码/);
    fireEvent.change(email, { target: { value: 'admin@example.test' } });
    fireEvent.change(password, { target: { value: 'pending-secret' } });
    email.focus();
    fireEvent.click(screen.getByRole('button', { name: '切换为英文' }));

    expect(screen.getByRole('heading', { name: 'Sign in to Platform Console' })).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>(/^Email/).value).toBe('admin@example.test');
    expect(screen.getByLabelText<HTMLInputElement>(/^Password/).value).toBe('pending-secret');
    expect(document.activeElement).toBe(screen.getByLabelText(/^Email/));

    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2);
    });
    const signal = fetch.mock.calls[1]?.[1]?.signal;
    fireEvent.click(screen.getByRole('button', { name: '切换为中文' }));

    expect(screen.getByRole('heading', { name: '登录 Platform Console' })).toBeTruthy();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(signal?.aborted).toBe(false);
  });

  it('logs in with the fixed Platform intent and clears the password form', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[{ path: '/', label: '首页', element: <h1>Platform 首页</h1> }]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'initial-secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'Platform 首页' })).toBeTruthy();
    expect(screen.queryByDisplayValue('initial-secret')).toBeNull();
    expect(jsonRequestBody(fetch.mock.calls[1])).toEqual({
      email: 'admin@example.test',
      password: 'initial-secret',
      contextType: 'PLATFORM',
    });
  });

  it('clears the password while login is pending and aborts the request when leaving', async () => {
    let resolveLogin: ((response: Response) => void) | undefined;
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            resolveLogin = resolve;
          }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    const view = render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );
    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'pending-secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(screen.queryByDisplayValue('pending-secret')).toBeNull();
    await waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2);
    });
    const signal = fetch.mock.calls[1]?.[1]?.signal;
    expect(signal?.aborted).toBe(false);
    view.unmount();
    expect(signal?.aborted).toBe(true);
    resolveLogin?.(new Response(null, { status: 401 }));
  });

  it('keeps a recoverable cold-start failure separate from the anonymous login', async () => {
    let now = 0;
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(problemResponse(503, 'REFRESH_LEASE_BUSY'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch, now: () => now },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[{ path: '/', label: '首页', element: <h1>恢复后的首页</h1> }]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    expect(await screen.findByRole('heading', { name: '暂时无法恢复会话' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '登录 Platform Console' })).toBeNull();
    expect(screen.queryByText('raw service detail')).toBeNull();

    now = 1_000;
    fireEvent.click(screen.getByRole('button', { name: '重试恢复' }));

    expect(await screen.findByRole('heading', { name: '恢复后的首页' })).toBeTruthy();
  });

  it('routes a restricted Platform session through first password change and back to login', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ contextState: 'PASSWORD_CHANGE_REQUIRED' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter initialEntries={['/protected']}>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[{ path: '/', label: '首页', element: <h1>Platform 首页</h1> }]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'initial-secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: '设置新密码' })).toBeTruthy();
    expect(screen.queryByDisplayValue('initial-secret')).toBeNull();
    fireEvent.change(screen.getByLabelText(/^新密码/), { target: { value: 'new-secret' } });
    fireEvent.click(screen.getByRole('button', { name: /更新密码/ }));

    expect(await screen.findByText('密码已更新，请使用新密码重新登录。')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '登录 Platform Console' })).toBeTruthy();
    expect(screen.queryByDisplayValue('new-secret')).toBeNull();
    expect(jsonRequestBody(fetch.mock.calls[2])).toEqual({
      newPassword: 'new-secret',
    });
  });

  it('keeps a failed first password change as a safe, retryable page state', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ contextState: 'PASSWORD_CHANGE_REQUIRED' }))
      .mockResolvedValueOnce(problemResponse(503, 'PASSWORD_CHANGE_UNAVAILABLE'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'initial-secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));
    fireEvent.change(await screen.findByLabelText(/^新密码/), {
      target: { value: 'rejected-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: '更新密码' }));

    expect(await screen.findByText('错误代码：PASSWORD_CHANGE_UNAVAILABLE')).toBeTruthy();
    expect(screen.queryByText('raw service detail')).toBeNull();

    fireEvent.change(screen.getByLabelText(/^新密码/), {
      target: { value: 'accepted-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: /更新密码/ }));

    expect(await screen.findByText('密码已更新，请使用新密码重新登录。')).toBeTruthy();
  });

  it('requires an explicit Platform slot logout instead of replacing an active session', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(409, 'SESSION_SLOT_ALREADY_ACTIVE'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      {
        realm: {},
        intent: 'PLATFORM',
        fetch,
        createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
      },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('当前 Platform 会话槽位已有活动会话。')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '先登出当前 Platform 会话' }));

    expect(await screen.findByRole('heading', { name: '登录 Platform Console' })).toBeTruthy();
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(fetch.mock.calls[2]?.[0]).toBe('https://api.example.test/api/v1/auth/logout');
  });

  it('returns to a validated in-memory protected path after login', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter initialEntries={['/oauth-clients?status=active#selected']}>
          <LocationProbe />
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[
              {
                path: '/oauth-clients',
                label: 'OAuth Client',
                element: <h1>OAuth Client 管理</h1>,
              },
            ]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    expect(screen.getByTestId('current-path').textContent).toBe('/login');
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'OAuth Client 管理' })).toBeTruthy();
    expect(screen.getByTestId('current-path').textContent).toBe(
      '/oauth-clients?status=active#selected',
    );
  });

  it.each([
    'https://outside.example/path',
    '//outside.example/path',
    'javascript:alert(1)',
    '/oauth-clients\\outside',
  ])('rejects an unsafe return address %s and uses the host default', async (unsafeReturnTo) => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter
          initialEntries={[{ pathname: '/login', state: { returnTo: unsafeReturnTo } }]}
        >
          <LocationProbe />
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[
              { path: '/', label: '首页', element: <h1>Platform 首页</h1> },
              {
                path: '/oauth-clients',
                label: 'OAuth Client',
                element: <h1>OAuth Client 管理</h1>,
              },
            ]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'Platform 首页' })).toBeTruthy();
    expect(screen.getByTestId('current-path').textContent).toBe('/');
  });

  it('shows global navigation only when authenticated and logs out only the Platform slot', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      {
        realm: {},
        intent: 'PLATFORM',
        fetch,
        createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
      },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[
              { path: '/', label: '首页', element: <h1>Platform 首页</h1> },
              {
                path: '/oauth-clients',
                label: 'OAuth Client',
                element: <h1>OAuth Client 管理</h1>,
              },
            ]}
          />
          <LocaleSwitcher />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    expect(
      await screen.findByRole('navigation', { name: 'Platform Console 全局导航' }),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: '首页' }).getAttribute('aria-current')).toBe('page');
    fireEvent.click(screen.getByRole('button', { name: '切换为英文' }));
    expect(
      screen.getByRole('navigation', { name: 'Platform Console global navigation' }),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: 'OAuth Client' }));
    expect(await screen.findByRole('heading', { name: 'OAuth Client 管理' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(
      await screen.findByRole('heading', { name: 'Sign in to Platform Console' }),
    ).toBeTruthy();
    expect(
      screen.queryByRole('navigation', { name: 'Platform Console global navigation' }),
    ).toBeNull();
    expect(jsonRequestBody(fetch.mock.calls[1])).toEqual({
      sessionSlot: 'PLATFORM',
    });
  });

  it('shows logoutPending without claiming server logout and allows an explicit retry', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockRejectedValueOnce(new TypeError('raw network failure'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      {
        realm: {},
        intent: 'PLATFORM',
        fetch,
        createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
      },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[{ path: '/', label: '首页', element: <h1>Platform 首页</h1> }]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '退出登录' }));

    expect(await screen.findByRole('heading', { name: '退出结果尚未确认' })).toBeTruthy();
    expect(screen.getByText('本页面已停止使用当前会话，但服务端是否完成退出仍未知。')).toBeTruthy();
    expect(screen.queryByText(/退出成功/)).toBeNull();
    expect(screen.queryByRole('navigation')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重试退出' }));

    expect(await screen.findByRole('heading', { name: '登录 Platform Console' })).toBeTruthy();
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('isolates a route render failure and returns through safe shell navigation', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const runtimeResult = createAuthenticationRuntimeAfterConfig(
      {
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      },
      {
        realm: {},
        intent: 'PLATFORM',
        fetch: () =>
          Promise.resolve(
            Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: 'recovered-token',
              tokenType: 'Bearer',
              expiresIn: 120,
            }),
          ),
      },
    );
    if (!runtimeResult.ok) {
      throw new Error('test runtime creation failed');
    }

    render(
      <DesignSystemProvider>
        <MemoryRouter initialEntries={['/broken']}>
          <AuthenticationShell
            applicationName="Platform Console"
            runtime={runtimeResult.runtime}
            defaultPath="/"
            routes={[
              { path: '/', label: '首页', element: <h1>Platform 首页</h1> },
              { path: '/broken', label: '故障页面', element: <BrokenRoute /> },
            ]}
          />
        </MemoryRouter>
      </DesignSystemProvider>,
    );

    expect(await screen.findByRole('heading', { name: '当前页面出现错误' })).toBeTruthy();
    expect(screen.queryByText('raw route render detail')).toBeNull();
    const errorHeading = screen.getByRole('heading', { name: '当前页面出现错误' });
    await waitFor(() => {
      expect(document.activeElement).toBe(errorHeading);
    });
    expect(screen.getByRole('status').textContent).toBe('当前页面出现错误');
    expect(screen.getByRole('navigation', { name: 'Platform Console 全局导航' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '返回首页' }));

    expect(await screen.findByRole('heading', { name: 'Platform 首页' })).toBeTruthy();
    consoleError.mockRestore();
  });
});

describe('AuthenticationRootErrorBoundary', () => {
  it('replaces a root Runtime failure with a safe reload surface', () => {
    const reload = vi.fn();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <DesignSystemProvider>
        <AuthenticationRootErrorBoundary applicationName="Platform Console" reload={reload}>
          <BrokenRoot />
        </AuthenticationRootErrorBoundary>
      </DesignSystemProvider>,
    );

    expect(screen.getByText('APPLICATION_FATAL')).toBeTruthy();
    expect(screen.queryByText('raw root runtime detail')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
    expect(reload).toHaveBeenCalledOnce();
    consoleError.mockRestore();
  });

  it('uses the last known Locale when a root failure escapes the Provider', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <AuthenticationRootErrorBoundary applicationName="Tenant Console" locale="en-US">
        <BrokenRoot />
      </AuthenticationRootErrorBoundary>,
    );

    expect(screen.getByRole('heading', { name: 'Tenant Console cannot continue' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeTruthy();
    consoleError.mockRestore();
  });
});

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="current-path">{location.pathname + location.search + location.hash}</p>;
}

function LocaleSwitcher() {
  const { setLocale } = useConsoleLocale();
  return (
    <>
      <button
        type="button"
        onClick={() => {
          setLocale('en-US');
        }}
      >
        切换为英文
      </button>
      <button
        type="button"
        onClick={() => {
          setLocale('zh-CN');
        }}
      >
        切换为中文
      </button>
    </>
  );
}

function BrokenRoute(): never {
  throw new Error('raw route render detail');
}

function BrokenRoot(): never {
  throw new Error('raw root runtime detail');
}

function problemResponse(status: number, code: string): Response {
  return Response.json(
    {
      type: `urn:saasforge:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: 'raw service title',
      status,
      code,
      detail: 'raw service detail',
      traceId: '0123456789abcdef0123456789abcdef',
    },
    {
      status,
      headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '1' },
    },
  );
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

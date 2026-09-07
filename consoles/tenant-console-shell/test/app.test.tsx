import { createRuntimeConfigBootstrap, type RuntimeConfigResult } from '@saas-forge/app-runtime';
import {
  platformResolvedBrandProfile,
  tenantConsolePlatformTitle,
  type BrandAssetPreloader,
} from '@saas-forge/design-system';
import {
  BrandApplicationProvider,
  ConsoleLocaleProvider,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { cleanup, fireEvent, render as renderReact, screen, waitFor } from '@testing-library/react';
import type { ComponentProps, ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  TenantConsoleShellApp as TenantConsoleShellAppUnderTest,
  type TenantConsoleRootProps,
} from '../src/app';

afterEach(cleanup);
beforeEach(() => {
  window.history.replaceState({}, '', '/');
});

const successfulBrandAssetPreloader: BrandAssetPreloader = ({ kind }) =>
  Promise.resolve({ loaded: true, mimeType: kind === 'logo' ? 'image/svg+xml' : 'image/png' });

function TenantConsoleShellApp(props: ComponentProps<typeof TenantConsoleShellAppUnderTest>) {
  return (
    <TenantConsoleShellAppUnderTest
      brandAssetPreloader={successfulBrandAssetPreloader}
      {...props}
    />
  );
}

function TenantConsoleTestRoot({ children, resolvedBrand }: TenantConsoleRootProps) {
  const { locale, setLocale } = useConsoleLocale();
  return (
    <BrandApplicationProvider resolvedBrand={resolvedBrand} surface="tenant" locale={locale}>
      <button
        type="button"
        onClick={() => {
          setLocale('en-US');
        }}
      >
        切换为英文
      </button>
      {children}
    </BrandApplicationProvider>
  );
}

function render(ui: ReactNode, initialLocale: 'zh-CN' | 'en-US' = 'zh-CN') {
  return renderReact(
    <ConsoleLocaleProvider initialLocale={initialLocale}>{ui}</ConsoleLocaleProvider>,
  );
}

describe('TenantConsoleShellApp', () => {
  it('uses the complete Platform brand before an authoritative Tenant Context exists', async () => {
    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
        realm={{}}
      />,
    );

    expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'SaaS Forge Logo' }).getAttribute('src')).toBe(
      platformResolvedBrandProfile.profile.logoUrl,
    );
    expect(document.title).toBe(tenantConsolePlatformTitle);
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      platformResolvedBrandProfile.profile.faviconUrl,
    );
  });

  it('atomically applies a complete Tenant brand after both assets are ready', async () => {
    const membership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const finishAssetLoads: Array<(result: Awaited<ReturnType<BrandAssetPreloader>>) => void> = [];
    const preloadAsset = vi.fn<BrandAssetPreloader>(
      () =>
        new Promise((resolve) => {
          finishAssetLoads.push(resolve);
        }),
    );

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={() =>
          Promise.resolve(
            tenantAccessToken('current-token', membership, [membership], {
              displayName: 'Current Brand',
              logoUrl: '/brands/current-logo.svg',
              faviconUrl: '/brands/current-favicon.svg',
              primaryColor: '#155EEF',
              accentColor: '#7A5AF8',
            }),
          )
        }
        brandAssetPreloader={preloadAsset}
        realm={{}}
      />,
    );

    await waitFor(() => {
      expect(preloadAsset).toHaveBeenCalledTimes(2);
    });
    expect(screen.getByRole('navigation', { name: 'SaaS Forge 全局导航' })).toBeTruthy();
    expect(screen.queryByText('Current Brand')).toBeNull();
    expect(document.title).toBe(tenantConsolePlatformTitle);

    for (const [index, finish] of finishAssetLoads.entries()) {
      const kind = preloadAsset.mock.calls[index]?.[0].kind;
      finish({ loaded: true, mimeType: kind === 'logo' ? 'image/svg+xml' : 'image/png' });
    }

    expect(await screen.findByText('Current Brand')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Current Brand Logo' }).getAttribute('src')).toBe(
      '/brands/current-logo.svg',
    );
    expect(document.title).toBe('Current Brand · SaaS Forge Tenant Console');
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      '/brands/current-favicon.svg',
    );
    const root = document.querySelector('.sf-design-system-root');
    expect(root?.getAttribute('data-brand')).toBe('tenant');
    expect(root?.getAttribute('style')).toContain('--sf-color-primary: #155EEF');
    expect(preloadAsset.mock.calls.map(([request]) => `${request.kind}:${request.url}`)).toEqual([
      'logo:/brands/current-logo.svg',
      'favicon:/brands/current-favicon.svg',
    ]);
  });

  it('rejects an incomplete Tenant brand without ending its authenticated context', async () => {
    const membership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const onBrandRejected = vi.fn();

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={() =>
          Promise.resolve(
            tenantAccessToken('current-token', membership, [membership], {
              displayName: 'Must Not Leak',
              logoUrl: undefined,
              faviconUrl: '/brands/rejected-favicon.svg',
              primaryColor: '#155EEF',
              accentColor: '#7A5AF8',
            }),
          )
        }
        onBrandRejected={onBrandRejected}
        realm={{}}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: 'SaaS Forge 全局导航' })).toBeTruthy();
    expect(screen.queryByText('Must Not Leak')).toBeNull();
    expect(screen.getByRole('img', { name: 'SaaS Forge Logo' }).getAttribute('src')).toBe(
      platformResolvedBrandProfile.profile.logoUrl,
    );
    expect(document.title).toBe(tenantConsolePlatformTitle);
    expect(document.querySelector('.sf-design-system-root')?.getAttribute('data-brand')).toBe(
      'platform',
    );
    expect(onBrandRejected).toHaveBeenCalledOnce();
    expect(onBrandRejected).toHaveBeenCalledWith('PROFILE_INVALID');
  });

  it('replaces the Tenant favicon with the Platform favicon after logout', async () => {
    expect(document.querySelector('link[rel~="icon"]')).toBeNull();
    const membership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const authenticationFetch = vi
      .fn()
      .mockResolvedValueOnce(
        tenantAccessToken('current-token', membership, [membership], {
          displayName: 'Current Brand',
          faviconUrl: '/brands/current-favicon.svg',
          primaryColor: '#155EEF',
          accentColor: '#7A5AF8',
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
    );
    expect(await screen.findByText('Current Brand')).toBeTruthy();
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      '/brands/current-favicon.svg',
    );
    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
    expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      platformResolvedBrandProfile.profile.faviconUrl,
    );
  });

  it('switches Tenant context, brand, and favicon only after the committed refresh succeeds', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const initialIcon = document.createElement('link');
    initialIcon.rel = 'icon';
    initialIcon.href = '/favicon.svg';
    document.head.append(initialIcon);
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        tenantAccessToken(
          'current-token',
          currentMembership,
          [currentMembership, targetMembership],
          {
            displayName: 'Current Brand',
            logoUrl: '/brands/current-logo.svg',
            faviconUrl: '/brands/current-favicon.svg',
            primaryColor: '#155EEF',
            accentColor: '#7A5AF8',
          },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(problemResponse(503, 'REFRESH_LEASE_BUSY'))
      .mockResolvedValueOnce(
        tenantAccessToken('target-token', targetMembership, [currentMembership, targetMembership], {
          displayName: 'Target Brand',
          logoUrl: '/brands/target-logo.svg',
          faviconUrl: '/brands/target-favicon.svg',
          primaryColor: '#7C3AED',
          accentColor: '#C026D3',
        }),
      );

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
    );

    expect(await screen.findByText('Current Brand')).toBeTruthy();
    expect(initialIcon.getAttribute('href')).toBe('/brands/current-favicon.svg');
    fireEvent.click(screen.getByRole('button', { name: '切换 Tenant' }));
    fireEvent.click(screen.getByRole('button', { name: '切换到 Target Tenant' }));

    expect(await screen.findByRole('heading', { name: 'Tenant 切换已提交' })).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Tenant 工作台' })).toBeNull();
    expect(await screen.findByText('错误代码：REFRESH_LEASE_BUSY')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'SaaS Forge Logo' }).getAttribute('src')).toBe(
      platformResolvedBrandProfile.profile.logoUrl,
    );
    expect(initialIcon.getAttribute('href')).toBe(platformResolvedBrandProfile.profile.faviconUrl);
    expect(document.title).toBe(tenantConsolePlatformTitle);
    expect(document.querySelector('.sf-design-system-root')?.getAttribute('data-brand')).toBe(
      'platform',
    );
    fireEvent.click(screen.getByRole('button', { name: '重试完成切换' }));

    expect(await screen.findByText('Target Brand')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Target Brand Logo' }).getAttribute('src')).toBe(
      '/brands/target-logo.svg',
    );
    await waitFor(() => {
      expect(initialIcon.getAttribute('href')).toBe('/brands/target-favicon.svg');
    });
    const root = document.querySelector('.sf-design-system-root');
    expect(root?.getAttribute('data-brand')).toBe('tenant');
    expect(root?.getAttribute('style')).toContain('--sf-color-primary: #7C3AED');
    initialIcon.remove();
  });

  it('keeps the current Tenant visible when the switch is rejected before commit', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        tenantAccessToken(
          'current-token',
          currentMembership,
          [currentMembership, targetMembership],
          {
            displayName: 'Current Brand',
            faviconUrl: '/brands/current-favicon.svg',
            primaryColor: '#7C3AED',
            accentColor: '#C026D3',
          },
        ),
      )
      .mockResolvedValueOnce(problemResponse(409, 'TENANT_CONTEXT_SWITCH_REJECTED'));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
      'en-US',
    );

    expect(await screen.findByText('Current Brand')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Switch Tenant' }));
    fireEvent.click(screen.getByRole('button', { name: 'Switch to Target Tenant' }));

    expect(await screen.findByText('Tenant switch rejected')).toBeTruthy();
    expect(screen.getByText('Error code: TENANT_CONTEXT_SWITCH_REJECTED')).toBeTruthy();
    expect(
      screen.getByRole('navigation', { name: 'Current Brand global navigation' }),
    ).toBeTruthy();
  });

  it('keeps the unknown switch retry target and sends no request when Locale changes', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        tenantAccessToken(
          'current-token',
          currentMembership,
          [currentMembership, targetMembership],
          {
            displayName: 'Current Tenant',
            faviconUrl: '/brands/current-favicon.svg',
            primaryColor: '#155EEF',
            accentColor: '#7A5AF8',
          },
        ),
      )
      .mockResolvedValueOnce(problemResponse(503, 'NETWORK_UNAVAILABLE'));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
    );

    expect(await screen.findByText('Current Tenant')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '切换 Tenant' }));
    fireEvent.click(screen.getByRole('button', { name: '切换到 Target Tenant' }));
    expect(await screen.findByText('Tenant 切换结果未知')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重试切换到 Target Tenant/ })).toBeTruthy();
    const requestCountBeforeLocaleChange = authenticationFetch.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: '切换为英文' }));

    expect(screen.getByText('Tenant switch result unknown')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Retry switching to Target Tenant/ })).toBeTruthy();
    expect(screen.getAllByText('Current Tenant')).not.toHaveLength(0);
    expect(authenticationFetch).toHaveBeenCalledTimes(requestCountBeforeLocaleChange);
  });

  it('ends the Tenant session when refresh is permanently rejected after commit', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        tenantAccessToken(
          'current-token',
          currentMembership,
          [currentMembership, targetMembership],
          {
            displayName: 'Current Brand',
            faviconUrl: '/brands/current-favicon.svg',
            primaryColor: '#7C3AED',
            accentColor: '#C026D3',
          },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(problemResponse(401, 'REFRESH_SESSION_INVALID'));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
      'en-US',
    );

    expect(await screen.findByText('Current Brand')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Switch Tenant' }));
    fireEvent.click(screen.getByRole('button', { name: 'Switch to Target Tenant' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to SaaS Forge' })).toBeTruthy();
    expect(screen.getByText('Tenant session ended')).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

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
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(loader)}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
      'en-US',
    );

    fireEvent.change(await screen.findByLabelText(/^Email/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Tenant workspace' })).toBeTruthy();
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
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
      'en-US',
    );

    fireEvent.change(await screen.findByLabelText(/^Email/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(
      await screen.findByText(
        'The current Identity has no Tenant it can enter. Contact a Tenant administrator.',
      ),
    ).toBeTruthy();
    expect(screen.getByText('Error code: ACCESS_CONTEXT_UNAVAILABLE')).toBeTruthy();
    expect(screen.getByRole('form', { name: 'Sign in to SaaS Forge' })).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('keeps an over-limit Membership response anonymous and provides a safe next step', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(409, 'ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED'));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
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
    expect(screen.getByRole('form', { name: '登录 SaaS Forge' })).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('requires an explicit Tenant slot logout instead of replacing an active Tenant Family', async () => {
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(problemResponse(409, 'SESSION_SLOT_ALREADY_ACTIVE'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
    );

    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'member@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('当前 Tenant 会话槽位已有活动会话。')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '先登出当前 Tenant 会话' }));

    expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
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
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
        authenticationFetch={authenticationFetch}
        realm={{}}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Tenant 工作台' })).toBeTruthy();
    expect(jsonRequestBody(authenticationFetch.mock.calls[0])).toEqual({ sessionSlot: 'TENANT' });

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));

    expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
    expect(jsonRequestBody(authenticationFetch.mock.calls[1])).toEqual({ sessionSlot: 'TENANT' });
  });

  it('mounts the Tenant route tree only after runtime configuration succeeds', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(loader)}
        authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
        realm={{}}
      />,
    );

    expect(screen.getByRole('heading', { name: '正在启动 SaaS Forge' })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: '登录 SaaS Forge' })).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
  });

  it('shows a stable bilingual configuration failure and retries only after user action', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_UNAVAILABLE' } })
      .mockResolvedValueOnce(success());

    render(
      <TenantConsoleShellApp
        root={TenantConsoleTestRoot}
        bootstrap={createRuntimeConfigBootstrap(loader)}
        authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
        realm={{}}
      />,
      'en-US',
    );

    expect(
      await screen.findByRole('heading', { name: 'SaaS Forge configuration is unavailable' }),
    ).toBeTruthy();
    expect(screen.getByText('CONFIG_UNAVAILABLE')).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to SaaS Forge' })).toBeTruthy();
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

function tenantAccessToken(
  accessToken: string,
  currentMembership: {
    readonly membershipId: string;
    readonly tenantId: string;
    readonly tenantDisplayName: string;
  },
  accessibleMemberships: readonly {
    readonly membershipId: string;
    readonly tenantId: string;
    readonly tenantDisplayName: string;
  }[],
  brandProfile: {
    readonly displayName: string;
    readonly logoUrl?: string;
    readonly faviconUrl: string;
    readonly primaryColor: string;
    readonly accentColor: string;
  },
): Response {
  return Response.json({
    contextState: 'ACCESS_TOKEN_ISSUED',
    accessToken,
    tokenType: 'Bearer',
    expiresIn: 120,
    tenantContext: {
      ...currentMembership,
      accessibleMemberships,
      brandProfile: { logoUrl: '/brands/test-logo.svg', ...brandProfile },
    },
  });
}

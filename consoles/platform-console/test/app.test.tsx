import { createRuntimeConfigBootstrap, type RuntimeConfigResult } from '@saas-forge/app-runtime';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import {
  AuthenticationRootErrorBoundary,
  BrandApplicationProvider,
  ConsoleLocaleProvider,
} from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

describe('PlatformConsoleApp', () => {
  it('keeps route render errors inside the safe application boundary', async () => {
    window.history.replaceState(null, '', '/');
    const descriptor = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
    if (descriptor?.set === undefined) throw new Error('Missing DOM text setter');
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    Object.defineProperty(Node.prototype, 'textContent', {
      ...descriptor,
      set(value: string | null) {
        if ((this as Node).nodeName === 'H1' && value === '登录 SaaS Forge')
          throw new Error('private-route-render-failure');
        descriptor.set?.call(this, value);
      },
    });
    try {
      render(
        <ConsoleLocaleProvider initialLocale="zh-CN">
          <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
            <AuthenticationRootErrorBoundary applicationName="SaaS Forge" locale="zh-CN">
              <PlatformConsoleApp
                bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
                authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
                realm={{}}
              />
            </AuthenticationRootErrorBoundary>
          </BrandApplicationProvider>
        </ConsoleLocaleProvider>,
      );
      expect(await screen.findByRole('heading', { name: 'SaaS Forge 无法继续运行' })).toBeTruthy();
      expect(document.body.textContent).not.toContain('private-route-render-failure');
    } finally {
      Object.defineProperty(Node.prototype, 'textContent', descriptor);
      errorLog.mockRestore();
    }
  });

  it.each([
    ['zh-CN', '当前身份暂时无法读取', '重新读取', '无平台管理员授权'],
    [
      'en-US',
      'Current identity is unavailable',
      'Reload identity',
      'No platform administrator authorization',
    ],
  ] as const)(
    'retries an unavailable authoritative identity in %s without inventing authorization',
    async (locale, failure, retry, denied) => {
      window.history.replaceState(null, '', '/');
      const authenticationFetch = vi
        .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
        .mockResolvedValueOnce(
          Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'memory-only-token',
            tokenType: 'Bearer',
            expiresIn: 120,
          }),
        )
        .mockResolvedValueOnce(
          Response.json(
            { code: 'TOKEN_REVOCATION_STATUS_UNAVAILABLE', status: 503 },
            { status: 503 },
          ),
        )
        .mockResolvedValueOnce(
          Response.json({
            identityId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
            email: 'reader@example.test',
            platformAdmin: false,
          }),
        );
      render(
        <ConsoleLocaleProvider initialLocale={locale}>
          <BrandApplicationProvider
            resolvedBrand={platformResolvedBrandProfile}
            surface="platform"
            locale={locale}
          >
            <PlatformConsoleApp
              bootstrap={createRuntimeConfigBootstrap(() => Promise.resolve(success()))}
              authenticationFetch={authenticationFetch}
              realm={{}}
            />
          </BrandApplicationProvider>
        </ConsoleLocaleProvider>,
      );
      expect(await screen.findByText(failure)).toBeTruthy();
      expect(screen.queryByText('reader@example.test')).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: retry }));
      expect(await screen.findByText('reader@example.test')).toBeTruthy();
      expect(screen.getByText(denied)).toBeTruthy();
      await waitFor(() => {
        expect(screen.queryByText(failure)).toBeNull();
      });
    },
  );

  it('creates the Platform authentication path only after runtime configuration succeeds', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));
    const authenticationFetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          identityId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
          email: 'admin@example.test',
          displayName: 'Platform administrator',
          platformAdmin: true,
        }),
      );

    const bootstrap = createRuntimeConfigBootstrap(loader);
    const realm = {};
    const view = render(
      <ConsoleLocaleProvider initialLocale="zh-CN">
        <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
          <PlatformConsoleApp
            bootstrap={bootstrap}
            authenticationFetch={authenticationFetch}
            realm={realm}
          />
        </BrandApplicationProvider>
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByRole('heading', { name: '正在启动 SaaS Forge' })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'SaaS Forge Logo' }).getAttribute('src')).toBe(
      platformResolvedBrandProfile.profile.logoUrl,
    );
    expect(document.title).toBe('SaaS Forge Platform Console');
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      platformResolvedBrandProfile.profile.faviconUrl,
    );
    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'Platform 总览' })).toBeTruthy();
    expect(await screen.findByText('admin@example.test')).toBeTruthy();
    expect(screen.getByText('Platform administrator')).toBeTruthy();
    expect(screen.getByText('平台管理员')).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
    expect(jsonRequestBody(authenticationFetch.mock.calls[1])).toEqual({
      email: 'admin@example.test',
      password: 'secret',
      contextType: 'PLATFORM',
    });

    view.rerender(
      <ConsoleLocaleProvider initialLocale="zh-CN">
        <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
          <PlatformConsoleApp
            bootstrap={bootstrap}
            authenticationFetch={authenticationFetch}
            realm={realm}
          />
        </BrandApplicationProvider>
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Platform 总览' })).toBeTruthy();
    expect(authenticationFetch).toHaveBeenCalledTimes(3);
  });

  it('shows a stable bilingual configuration failure and retries only after user action', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_UNAVAILABLE' } })
      .mockResolvedValueOnce(success());

    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <BrandApplicationProvider
          resolvedBrand={platformResolvedBrandProfile}
          surface="platform"
          locale="en-US"
        >
          <PlatformConsoleApp
            bootstrap={createRuntimeConfigBootstrap(loader)}
            authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
            realm={{}}
          />
        </BrandApplicationProvider>
      </ConsoleLocaleProvider>,
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

import { createRuntimeConfigBootstrap, type RuntimeConfigResult } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { ConsoleLocaleProvider } from '@saas-forge/react-shell';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

describe('PlatformConsoleApp', () => {
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
      );

    const bootstrap = createRuntimeConfigBootstrap(loader);
    const realm = {};
    const view = render(
      <ConsoleLocaleProvider initialLocale="zh-CN">
        <DesignSystemProvider>
          <PlatformConsoleApp
            bootstrap={bootstrap}
            authenticationFetch={authenticationFetch}
            realm={realm}
          />
        </DesignSystemProvider>
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByRole('heading', { name: '正在启动 Platform Console' })).toBeTruthy();
    fireEvent.change(await screen.findByLabelText(/^邮箱/), {
      target: { value: 'admin@example.test' },
    });
    fireEvent.change(screen.getByLabelText(/^密码/), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('heading', { name: 'Platform 总览' })).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
    expect(jsonRequestBody(authenticationFetch.mock.calls[1])).toEqual({
      email: 'admin@example.test',
      password: 'secret',
      contextType: 'PLATFORM',
    });

    view.rerender(
      <ConsoleLocaleProvider initialLocale="zh-CN">
        <DesignSystemProvider>
          <PlatformConsoleApp
            bootstrap={bootstrap}
            authenticationFetch={authenticationFetch}
            realm={realm}
          />
        </DesignSystemProvider>
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Platform 总览' })).toBeTruthy();
    expect(authenticationFetch).toHaveBeenCalledTimes(2);
  });

  it('shows a stable bilingual configuration failure and retries only after user action', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_UNAVAILABLE' } })
      .mockResolvedValueOnce(success());

    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <DesignSystemProvider locale="en-US">
          <PlatformConsoleApp
            bootstrap={createRuntimeConfigBootstrap(loader)}
            authenticationFetch={() => Promise.resolve(new Response(null, { status: 401 }))}
            realm={{}}
          />
        </DesignSystemProvider>
      </ConsoleLocaleProvider>,
    );

    expect(
      await screen.findByRole('heading', { name: 'Platform Console configuration is unavailable' }),
    ).toBeTruthy();
    expect(screen.getByText('CONFIG_UNAVAILABLE')).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(
      await screen.findByRole('heading', { name: 'Sign in to Platform Console' }),
    ).toBeTruthy();
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

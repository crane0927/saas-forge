import { createRuntimeConfigBootstrap, type RuntimeConfigResult } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PlatformConsoleApp } from '../src/app';

afterEach(cleanup);

describe('PlatformConsoleApp', () => {
  it('mounts the Platform route tree only after runtime configuration succeeds', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));

    render(
      <DesignSystemProvider>
        <PlatformConsoleApp bootstrap={createRuntimeConfigBootstrap(loader)} />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('heading', { name: '正在启动 Platform Console' })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: '页面不存在' })).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();
    expect(screen.getByText('Platform Console 尚未提供此路由。')).toBeTruthy();
  });

  it('shows a stable configuration failure and retries only after user action', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_UNAVAILABLE' } })
      .mockResolvedValueOnce(success());

    render(
      <DesignSystemProvider>
        <PlatformConsoleApp bootstrap={createRuntimeConfigBootstrap(loader)} />
      </DesignSystemProvider>,
    );

    expect(await screen.findByText('CONFIG_UNAVAILABLE')).toBeTruthy();
    expect(loader).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('heading', { name: '页面不存在' })).toBeTruthy();
    expect(loader).toHaveBeenCalledTimes(2);
  });
});

function success(): RuntimeConfigResult {
  return {
    ok: true,
    config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
  };
}

import { describe, expect, it, vi } from 'vitest';

import {
  createRuntimeConfigBootstrap,
  type BootstrapState,
  type RuntimeConfigResult,
} from '../src';

describe('createRuntimeConfigBootstrap', () => {
  it('transitions from loading to ready', async () => {
    const loader = vi.fn(() => Promise.resolve(success()));
    const states: BootstrapState[] = [];
    const bootstrap = createRuntimeConfigBootstrap(loader);
    bootstrap.subscribe((state) => states.push(state));

    await expect(bootstrap.start()).resolves.toEqual({
      status: 'ready',
      attempt: 1,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
    });
    expect(states.map(({ status }) => status)).toEqual(['loading', 'ready']);
    expect(loader).toHaveBeenCalledOnce();
  });

  it('stays failed until the user triggers retry', async () => {
    const loader = vi
      .fn<() => Promise<RuntimeConfigResult>>()
      .mockResolvedValueOnce({ ok: false, error: { code: 'CONFIG_NOT_FOUND' } })
      .mockResolvedValueOnce(success());
    const bootstrap = createRuntimeConfigBootstrap(loader);

    await expect(bootstrap.start()).resolves.toEqual({
      status: 'failed',
      attempt: 1,
      error: { code: 'CONFIG_NOT_FOUND' },
    });
    expect(loader).toHaveBeenCalledOnce();
    expect(bootstrap.getState()).toEqual({
      status: 'failed',
      attempt: 1,
      error: { code: 'CONFIG_NOT_FOUND' },
    });

    await expect(bootstrap.retry()).resolves.toEqual({
      status: 'ready',
      attempt: 2,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
    });
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it('does not start another load while loading or after success', async () => {
    let resolveLoad: ((result: RuntimeConfigResult) => void) | undefined;
    const loader = vi.fn(
      () =>
        new Promise<RuntimeConfigResult>((resolve) => {
          resolveLoad = resolve;
        }),
    );
    const bootstrap = createRuntimeConfigBootstrap(loader);

    const start = bootstrap.start();
    await Promise.resolve();
    const duplicateStart = bootstrap.start();
    await expect(bootstrap.retry()).resolves.toEqual({ status: 'loading', attempt: 1 });
    expect(loader).toHaveBeenCalledOnce();

    resolveLoad?.(success());
    await start;
    await expect(duplicateStart).resolves.toEqual({
      status: 'ready',
      attempt: 1,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
    });
    await bootstrap.start();
    await bootstrap.retry();
    expect(loader).toHaveBeenCalledOnce();
  });

  it('converts an unexpected loader rejection to a safe unavailable failure', async () => {
    const bootstrap = createRuntimeConfigBootstrap(() =>
      Promise.reject(new Error('stack and deployment detail')),
    );

    await expect(bootstrap.start()).resolves.toEqual({
      status: 'failed',
      attempt: 1,
      error: { code: 'CONFIG_UNAVAILABLE' },
    });
  });
});

function success(): RuntimeConfigResult {
  return {
    ok: true,
    config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
  };
}

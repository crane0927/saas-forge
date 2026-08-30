import { describe, expect, it, vi } from 'vitest';

import {
  loadRuntimeConfig,
  parseRuntimeConfig,
  RUNTIME_CONFIG_PATH,
  type RuntimeConfigErrorCode,
} from '../src';

describe('parseRuntimeConfig', () => {
  it('accepts the known contract and normalizes the API origin', () => {
    expect(
      parseRuntimeConfig({ schemaVersion: 1, apiBaseUrl: 'https://api.example.test/' }),
    ).toEqual({
      ok: true,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
    });
  });

  it.each([
    undefined,
    null,
    [],
    {},
    { schemaVersion: 1 },
    { apiBaseUrl: 'https://api.example.test' },
    { schemaVersion: 2, apiBaseUrl: 'https://api.example.test' },
    { schemaVersion: 1, apiBaseUrl: 'https://api.example.test', extra: true },
    { schemaVersion: 1, apiBaseUrl: 42 },
  ])('rejects an invalid field or version contract', (input) => {
    expect(parseRuntimeConfig(input)).toEqual(failure('CONFIG_INVALID'));
  });

  it.each([
    '',
    'https://',
    '/api',
    'http://api.example.test',
    'https://user@api.example.test',
    'https://user:secret@api.example.test',
    'https://api.example.test/v1',
    'https://api.example.test/%2e',
    'https://api.example.test//',
    'https://api.example.test?tenant=one',
    'https://api.example.test#section',
    'https://api.example.test?',
    'https://api.example.test#',
    ' https://api.example.test',
  ])('rejects a value that is not a safe absolute HTTPS origin: %s', (apiBaseUrl) => {
    expect(parseRuntimeConfig({ schemaVersion: 1, apiBaseUrl })).toEqual(
      failure('API_ORIGIN_INVALID'),
    );
  });
});

describe('loadRuntimeConfig', () => {
  it('loads the same-origin path while bypassing the browser cache', async () => {
    const fetchConfig = vi.fn(() =>
      Promise.resolve(Response.json({ schemaVersion: 1, apiBaseUrl: 'https://api.example.test' })),
    );

    await expect(loadRuntimeConfig(fetchConfig)).resolves.toEqual({
      ok: true,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
    });
    expect(fetchConfig).toHaveBeenCalledOnce();
    expect(fetchConfig).toHaveBeenCalledWith(RUNTIME_CONFIG_PATH, {
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
    });
  });

  it.each([
    [404, 'CONFIG_NOT_FOUND'],
    [500, 'CONFIG_UNAVAILABLE'],
  ] as const)('classifies HTTP %i without returning response content', async (status, code) => {
    const fetchConfig = vi.fn(() => Promise.resolve(new Response('deployment detail', { status })));

    await expect(loadRuntimeConfig(fetchConfig)).resolves.toEqual(failure(code));
  });

  it('classifies a network failure without returning its cause', async () => {
    const fetchConfig = vi.fn(() => Promise.reject(new Error('internal network detail')));

    await expect(loadRuntimeConfig(fetchConfig)).resolves.toEqual(failure('CONFIG_UNAVAILABLE'));
  });

  it('classifies an unreadable successful response as invalid configuration', async () => {
    const fetchConfig = vi.fn(() => Promise.resolve(new Response('{', { status: 200 })));

    await expect(loadRuntimeConfig(fetchConfig)).resolves.toEqual(failure('CONFIG_INVALID'));
  });

  it.each([
    [{ schemaVersion: 2, apiBaseUrl: 'https://api.example.test' }, 'CONFIG_INVALID'],
    [{ schemaVersion: 1, apiBaseUrl: 'http://api.example.test' }, 'API_ORIGIN_INVALID'],
  ] as const)('preserves parser failure classification through loading', async (body, code) => {
    const fetchConfig = vi.fn(() => Promise.resolve(Response.json(body)));

    await expect(loadRuntimeConfig(fetchConfig)).resolves.toEqual(failure(code));
  });
});

function failure(code: RuntimeConfigErrorCode) {
  return { ok: false, error: { code } } as const;
}

import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { OAuthClientCreate } from '../src/oauth-client-management';

afterEach(cleanup);
it('clears a once-only Secret and never offers to reveal it again', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 120,
      }),
    )
    .mockResolvedValueOnce(
      Response.json({
        clientId: '019535d9-0000-7000-8000-000000000002',
        displayName: 'Application',
        allowedScopes: ['runtime:read'],
        status: 'ACTIVE',
        createdAt: '2026-09-14T00:00:00Z',
        updatedAt: '2026-09-14T00:00:00Z',
        clientSecret: 'test-only-one-time-value',
      }),
    );
  const created = createAuthenticationRuntimeAfterConfig(
    { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
    { realm: {}, intent: 'PLATFORM', fetch },
  );
  if (!created.ok) throw new Error('Runtime unavailable');
  await created.runtime.login({ email: 'admin@example.test', password: 'password' });
  render(
    <DesignSystemProvider locale="zh-CN">
      <MemoryRouter>
        <OAuthClientCreate client={created.runtime.client} locale="zh-CN" />
      </MemoryRouter>
    </DesignSystemProvider>,
  );
  fireEvent.change(screen.getByLabelText(/^名称/), { target: { value: 'Application' } });
  fireEvent.click(screen.getByRole('button', { name: '创建接入凭据' }));
  await screen.findByRole('status', { name: '接入 Secret' });
  fireEvent.click(screen.getByRole('button', { name: '我已保存，关闭展示' }));
  expect(screen.queryByRole('status', { name: '接入 Secret' })).toBeNull();
  expect(screen.queryByRole('button', { name: '创建接入凭据' })).toBeNull();
  expect(fetch).toHaveBeenCalledTimes(2);
});

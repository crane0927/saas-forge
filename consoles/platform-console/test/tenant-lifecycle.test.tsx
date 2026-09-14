import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { TenantLifecycleSection } from '../src/tenant-lifecycle';

afterEach(cleanup);
it('does not offer resumption while a freeze is still pending', async () => {
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
        tenantId: '019535d9-0000-7000-8000-000000000002',
        state: 'PENDING',
        action: 'SUSPEND',
        canContinue: true,
        canSuspend: false,
        canResume: false,
        canRecoverSuspension: false,
        operationId: '019535d9-0000-7000-8000-000000000003',
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
      <TenantLifecycleSection
        client={created.runtime.client}
        locale="zh-CN"
        tenantId="019535d9-0000-7000-8000-000000000002"
        refreshVersion={0}
      />
    </DesignSystemProvider>,
  );
  await screen.findByText('冻结尚未完成');
  expect(screen.queryByRole('button', { name: '解除冻结' })).toBeNull();
  expect(screen.getByRole('button', { name: '继续处理' })).toBeTruthy();
});

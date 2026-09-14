import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { AuthenticationShell, ConsoleLocaleProvider } from '../src';

afterEach(cleanup);

function Location() {
  return <output aria-label="location">{useLocation().hash}</output>;
}

it('clears the email fragment and establishes a password without attempting session recovery', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  const result = createAuthenticationRuntimeAfterConfig(
    { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
    { realm: {}, intent: 'TENANT', fetch },
  );
  if (!result.ok) throw new Error('Runtime unavailable');
  render(
    <ConsoleLocaleProvider initialLocale="zh-CN">
      <DesignSystemProvider locale="zh-CN">
        <MemoryRouter initialEntries={[`/password-setup#token=${'a'.repeat(43)}`]}>
          <Location />
          <AuthenticationShell
            applicationName="Tenant Console"
            runtime={result.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>
    </ConsoleLocaleProvider>,
  );
  await waitFor(() => {
    expect(screen.getByLabelText('location').textContent).toBe('');
  });
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText(/^新密码/), { target: { value: 'valid-password' } });
  fireEvent.click(await screen.findByRole('button', { name: /设置密码$/ }));
  await screen.findByText('密码已设置，请使用新密码登录。');
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(result.runtime.getState().status).toBe('anonymous');
});
it('lets the customer correct a rejected password using the same unconsumed link', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json(
        {
          code: 'PASSWORD_COMPROMISED',
          type: 'urn:saas.forge:problem:password-compromised',
          title: 'Rejected',
          detail: 'Rejected',
          status: 400,
          traceId: '0123456789abcdef0123456789abcdef',
        },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )
    .mockResolvedValue(new Response(null, { status: 204 }));
  const result = createAuthenticationRuntimeAfterConfig(
    { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
    { realm: {}, intent: 'TENANT', fetch },
  );
  if (!result.ok) throw new Error('Runtime unavailable');
  render(
    <ConsoleLocaleProvider initialLocale="zh-CN">
      <DesignSystemProvider locale="zh-CN">
        <MemoryRouter initialEntries={[`/password-setup#token=${'a'.repeat(43)}`]}>
          <Location />
          <AuthenticationShell
            applicationName="Tenant Console"
            runtime={result.runtime}
            defaultPath="/"
            routes={[]}
          />
        </MemoryRouter>
      </DesignSystemProvider>
    </ConsoleLocaleProvider>,
  );
  await waitFor(() => {
    expect(screen.getByLabelText('location').textContent).toBe('');
  });
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText(/^新密码/), { target: { value: 'valid-password' } });
  fireEvent.click(await screen.findByRole('button', { name: /设置密码$/ }));
  await screen.findByText('请使用 12 至 128 个字符、不含空白且未泄露的密码。');
  fireEvent.change(screen.getByLabelText(/^新密码/), {
    target: { value: 'another-valid-password' },
  });
  fireEvent.click(await screen.findByRole('button', { name: /设置密码$/ }));
  await screen.findByText('密码已设置，请使用新密码登录。');
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(result.runtime.getState().status).toBe('anonymous');
});

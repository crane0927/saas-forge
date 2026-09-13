import {
  createAuthenticationRuntimeAfterConfig,
  type AuthenticationFetch,
} from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AuthenticationShell,
  ConsoleLocaleProvider,
  FormExitGuardProvider,
  useFormExitGuard,
} from '../src';

afterEach(cleanup);

function Editor({ dirty }: { readonly dirty: boolean }) {
  useFormExitGuard(dirty);
  return <input aria-label="Draft" defaultValue="Keep this draft" />;
}
function Page() {
  const [dirty, setDirty] = useState(false);
  const [visible, setVisible] = useState(true);
  return (
    <>
      {visible ? <Editor dirty={dirty} /> : null}
      <button
        onClick={() => {
          setDirty(true);
        }}
      >
        Edit
      </button>
      <button
        onClick={() => {
          setDirty(false);
        }}
      >
        Save
      </button>
      <button
        onClick={() => {
          setVisible(false);
        }}
      >
        Remove editor
      </button>
    </>
  );
}

describe.each(['zh-CN', 'en-US'] as const)('会话退出表单保护 %s', (locale) => {
  const text = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  async function mount() {
    const fetch = vi
      .fn<AuthenticationFetch>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'test-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValue(new Response(null, { status: 204 }));
    const result = createAuthenticationRuntimeAfterConfig(
      { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
      { realm: {}, intent: 'PLATFORM', fetch },
    );
    if (!result.ok) throw new Error('Invalid fixture');
    render(
      <ConsoleLocaleProvider initialLocale={locale}>
        <DesignSystemProvider locale={locale}>
          <FormExitGuardProvider>
            <MemoryRouter>
              <AuthenticationShell
                applicationName="Console"
                runtime={result.runtime}
                defaultPath="/"
                routes={[{ path: '/', label: 'Home', element: <Page /> }]}
              />
            </MemoryRouter>
          </FormExitGuardProvider>
        </DesignSystemProvider>
      </ConsoleLocaleProvider>,
    );
    await screen.findByRole('textbox', { name: 'Draft' });
    return fetch;
  }
  it('脏表单继续编辑保留输入，放弃后才结束会话', async () => {
    const fetch = await mount();
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.click(screen.getByRole('button', { name: text('退出登录', 'Sign out') }));
    expect(
      await screen.findByRole('dialog', {
        name: text('放弃未保存的修改？', 'Discard unsaved changes?'),
      }),
    ).toBeTruthy();
    expect(fetch).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: text('继续编辑', 'Continue editing') }));
    expect(screen.getByRole<HTMLInputElement>('textbox', { name: 'Draft' }).value).toBe(
      'Keep this draft',
    );
    expect(fetch).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: text('退出登录', 'Sign out') }));
    fireEvent.click(
      await screen.findByRole('button', { name: text('放弃修改', 'Discard changes') }),
    );
    await waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2);
    });
    const request = fetch.mock.calls[1][0];
    expect(request instanceof Request ? request.url : request.toString()).toContain('/logout');
  });
  it.each(['clean', 'Save', 'Remove editor'])('%s 后可直接退出', async (action) => {
    const fetch = await mount();
    if (action !== 'clean') {
      fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
      fireEvent.click(screen.getByRole('button', { name: action }));
    }
    fireEvent.click(screen.getByRole('button', { name: text('退出登录', 'Sign out') }));
    await waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2);
    });
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

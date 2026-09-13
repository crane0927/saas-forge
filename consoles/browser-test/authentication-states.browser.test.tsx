import { useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { MemoryRouter } from 'react-router';
import { createAuthenticationRuntimeAfterConfig } from '../shared/app-runtime/src';
import { DesignSystemProvider, type DesignSystemLocale } from '../shared/design-system/src';
import { AuthenticationShell, ConsoleLocaleProvider } from '../shared/react-shell/src';
import { auditAccessibility } from '../shared/design-system/browser-test/accessibility-audit';

let root: Root | undefined;
let container: HTMLDivElement | undefined;
afterEach(() => {
  root?.unmount();
  container?.remove();
  root = undefined;
  container = undefined;
  localStorage.clear();
});

for (const locale of ['zh-CN', 'en-US'] as const) {
  for (const dark of [false, true]) {
    for (const width of [1280, 390]) {
      for (const intent of ['PLATFORM', 'TENANT'] as const) {
        const variant = `${intent}-${locale}-${dark ? 'dark' : 'light'}-${String(width)}`;
        describe(variant, () => {
          it('恢复中→失败→显式重试→匿名登录的公开画面与状态', async () => {
            await page.viewport(width, 900);
            document.documentElement.lang = locale;
            let failRecovery: ((error: Error) => void) | undefined;
            let requestCount = 0;
            const fetch = () => {
              requestCount += 1;
              return requestCount === 1
                ? new Promise<Response>((_resolve, reject) => {
                    failRecovery = reject;
                  })
                : Promise.resolve(new Response(null, { status: 401 }));
            };
            const runtime = createAuthenticationRuntimeAfterConfig(
              { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
              { intent, realm: {}, fetch },
            );
            if (!runtime.ok) throw new Error('Runtime configuration failed');
            container = document.createElement('div');
            container.dataset.testid = 'authentication-surface';
            document.body.append(container);
            root = createRoot(container);
            root.render(<Fixture locale={locale} dark={dark} runtime={runtime.runtime} />);
            const loading = locale === 'zh-CN' ? '正在启动 Console' : 'Starting Console';
            await expect.element(page.getByRole('heading', { name: loading })).toBeVisible();
            await verifyPicture(`recovering-${variant}`);
            await expect.poll(() => requestCount).toBe(1);
            failRecovery?.(new TypeError('private-network-detail'));
            const failure =
              locale === 'zh-CN' ? '暂时无法恢复会话' : 'Unable to recover the session right now';
            await expect.element(page.getByRole('heading', { name: failure })).toBeVisible();
            await expect.element(page.getByRole('navigation')).not.toBeInTheDocument();
            await expect.element(page.getByText('private-network-detail')).not.toBeInTheDocument();
            await verifyPicture(`recovery-failed-${variant}`);
            expect(requestCount).toBe(1);
            await page
              .getByRole('button', { name: locale === 'zh-CN' ? '重试恢复' : 'Retry recovery' })
              .click();
            await expect
              .element(
                page.getByRole('heading', {
                  name: locale === 'zh-CN' ? '登录 Console' : 'Sign in to Console',
                }),
              )
              .toBeVisible();
            expect(requestCount).toBe(2);
            expect(runtime.runtime.getState().status).toBe('anonymous');
          });
        });
      }
    }
  }
}

function Fixture({
  locale,
  dark,
  runtime,
}: {
  readonly locale: DesignSystemLocale;
  readonly dark: boolean;
  readonly runtime: import('../shared/app-runtime/src').AuthenticationRuntime;
}) {
  const [routes] = useState(() => [{ path: '/', label: 'Home', element: <h1>Home</h1> }]);
  return (
    <ConsoleLocaleProvider initialLocale={locale}>
      <DesignSystemProvider locale={locale} forcedColorScheme={dark ? 'dark' : 'light'}>
        <MemoryRouter>
          <AuthenticationShell
            applicationName="Console"
            runtime={runtime}
            routes={routes}
            defaultPath="/"
          />
        </MemoryRouter>
      </DesignSystemProvider>
    </ConsoleLocaleProvider>
  );
}

async function verifyPicture(name: string) {
  await document.fonts.ready;
  expect((await auditAccessibility(document.body)).violations).toEqual([]);
  expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth);
  if (import.meta.env.SF_VISUAL_SNAPSHOTS !== 'false') {
    await expect(page.getByTestId('authentication-surface')).toMatchScreenshot(name);
  }
}

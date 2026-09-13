import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { PlatformConsoleApp } from '../platform-console/src/app';
import {
  TenantConsoleShellApp,
  type TenantConsoleRootProps,
} from '../tenant-console-shell/src/app';
import { createRuntimeConfigBootstrap, type AuthenticationFetch } from '../shared/app-runtime/src';
import { platformResolvedBrandProfile } from '../shared/design-system/src';
import { auditAccessibility } from '../shared/design-system/browser-test/accessibility-audit';
import {
  BrandApplicationProvider,
  ConsoleLocaleProvider,
  ConsoleLocaleSelector,
  useConsoleLocale,
} from '../shared/react-shell/src';

let root: Root;
let container: HTMLDivElement;
afterEach(() => {
  root.unmount();
  container.remove();
  vi.restoreAllMocks();
  window.localStorage.clear();
  window.history.replaceState({}, '', '/');
});
function TenantRoot({ children, resolvedBrand }: TenantConsoleRootProps) {
  const { locale } = useConsoleLocale();
  return (
    <BrandApplicationProvider resolvedBrand={resolvedBrand} surface="tenant" locale={locale}>
      <ConsoleLocaleSelector />
      {children}
    </BrandApplicationProvider>
  );
}
function Fixture({ tenant }: { readonly tenant: boolean }) {
  const { locale } = useConsoleLocale();
  const [fixture] = useState(() => ({
    bootstrap: createRuntimeConfigBootstrap(() =>
      Promise.resolve({
        ok: true,
        config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
      }),
    ),
    realm: {},
    authenticationFetch: ((input) => {
      const url = new URL(input instanceof Request ? input.url : input);
      return Promise.resolve(
        url.pathname.endsWith('/refresh')
          ? new Response(null, { status: 401 })
          : Response.json(
              {
                type: tenant
                  ? 'urn:saasforge:problem:access-context-unavailable'
                  : 'urn:saasforge:problem:session-slot-already-active',
                detail: 'Fixture only',
                traceId: '0123456789abcdef0123456789abcdef',
                title: 'Unavailable',
                status: 403,
                code: tenant ? 'ACCESS_CONTEXT_UNAVAILABLE' : 'SESSION_SLOT_ALREADY_ACTIVE',
              },
              { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
            ),
      );
    }) satisfies AuthenticationFetch,
  }));
  return tenant ? (
    <TenantConsoleShellApp root={TenantRoot} {...fixture} />
  ) : (
    <BrandApplicationProvider
      resolvedBrand={platformResolvedBrandProfile}
      surface="platform"
      locale={locale}
    >
      <ConsoleLocaleSelector />
      <PlatformConsoleApp {...fixture} />
    </BrandApplicationProvider>
  );
}
function mount(tenant: boolean) {
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  root.render(
    <ConsoleLocaleProvider initialLocale="zh-CN">
      <Fixture tenant={tenant} />
    </ConsoleLocaleProvider>,
  );
}

describe('登录卡片的真实消费者交互', () => {
  for (const tenant of [false, true])
    for (const dark of [false, true]) {
      it(`${tenant ? 'Tenant' : 'Platform'} ${dark ? 'dark' : 'light'} 保持角标、图标与长提示可读`, async () => {
        const original = window.matchMedia.bind(window);
        vi.spyOn(window, 'matchMedia').mockImplementation((query) =>
          query === '(prefers-color-scheme: dark)'
            ? {
                media: query,
                matches: dark,
                onchange: null,
                addEventListener: () => undefined,
                removeEventListener: () => undefined,
                addListener: () => undefined,
                removeListener: () => undefined,
                dispatchEvent: () => true,
              }
            : original(query),
        );
        await page.viewport(dark ? 320 : 1440, 900);
        mount(tenant);
        await expect.element(page.getByRole('heading', { name: '登录 SaaS Forge' })).toBeVisible();
        expect(document.querySelector('.sf-login-name .sf-login-entry')?.textContent).toBe(
          tenant ? '租户控制台' : '平台管理',
        );
        expect(document.querySelectorAll('#console-locale')).toHaveLength(1);
        expect(document.querySelector('.sf-login-tools #console-locale')).not.toBeNull();
        await page.getByRole('textbox', { name: '邮箱' }).fill('demo@example.test');
        const password = page.getByLabelText(/^密码/);
        await password.fill('example-only');
        // Ant Password 自带可访问的图标切换，不以新的文字按钮替换。
        const toggle = container.querySelector<HTMLElement>('.ant-input-password-icon');
        expect(toggle).not.toBeNull();
        toggle?.focus();
        await userEvent.keyboard('{Enter}');
        expect(password.element().getAttribute('type')).toBe('text');
        await userEvent.keyboard('{Enter}');
        expect(password.element().getAttribute('type')).toBe('password');
        await page.getByRole('button', { name: '登录', exact: true }).click();
        await expect.element(page.getByRole('alert')).toBeVisible();
        await expect
          .element(
            page.getByText(tenant ? '无法进入 Tenant' : '当前 Platform 会话槽位已有活动会话。', {
              exact: true,
            }),
          )
          .toBeVisible();
        expect((password.element() as HTMLInputElement).value).toBe('');
        const violations = (await auditAccessibility(document.body)).violations;
        expect(
          violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.failureSummary) })),
        ).toEqual([]);
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(dark ? 320 : 1440);
        if (import.meta.env.SF_VISUAL_SNAPSHOTS !== 'false')
          await expect
            .element(page.getByRole('main'))
            .toMatchScreenshot(
              `login-${tenant ? 'tenant' : 'platform'}-${dark ? 'dark-320' : 'light-1440'}`,
            );
      });
    }
  it('英文窄屏角标与语言切换保留表单输入', async () => {
    await page.viewport(320, 800);
    mount(false);
    await expect.element(page.getByRole('heading', { name: '登录 SaaS Forge' })).toBeVisible();
    await page.getByLabelText(/^邮箱/).fill('example@example.test');
    await page.getByLabelText(/^密码/).fill('fixture-password');
    page.getByRole('combobox', { name: 'Language / 语言' }).element().focus();
    await userEvent.keyboard('{Enter}{ArrowDown}{Enter}');
    await expect
      .element(page.getByRole('heading', { name: 'Sign in to SaaS Forge' }))
      .toBeVisible();
    expect((page.getByLabelText(/^Email/).element() as HTMLInputElement).value).toBe(
      'example@example.test',
    );
    expect((page.getByLabelText(/^Password/).element() as HTMLInputElement).value).toBe(
      'fixture-password',
    );
    expect(document.querySelector('.sf-login-entry')?.textContent).toBe('Platform admin');
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(320);
    if (import.meta.env.SF_VISUAL_SNAPSHOTS !== 'false')
      await expect.element(page.getByRole('main')).toMatchScreenshot('login-english-320');
  });
});

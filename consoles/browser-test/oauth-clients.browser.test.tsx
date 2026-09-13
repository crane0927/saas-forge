import { createRuntimeConfigBootstrap, type AuthenticationFetch } from '../shared/app-runtime/src';
import { platformResolvedBrandProfile } from '../shared/design-system/src';
import { BrandApplicationProvider, ConsoleLocaleProvider } from '../shared/react-shell/src';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { PlatformConsoleApp } from '../platform-console/src/app';
import { auditAccessibility } from '../shared/design-system/browser-test/accessibility-audit';

let root: Root | undefined;
let container: HTMLDivElement | undefined;
afterEach(() => {
  root?.unmount();
  container?.remove();
});
const id = '019535d9-0000-7000-8000-000000000002';
const detail = {
  clientId: id,
  displayName: 'IAM worker',
  clientType: 'RESERVED_SERVICE',
  reservedServiceKey: 'IAM',
  allowedScopes: ['tenant-access:membership:read'],
  status: 'ACTIVE',
  createdAt: '2026-09-11T00:00:00.000Z',
  updatedAt: '2026-09-11T00:00:00.000Z',
  revokedAt: null,
};
// HTTP 夹具只证明页面和共享 Runtime 行为；真实服务验收另行记录。
function mount(
  fetch: AuthenticationFetch,
  locale: 'zh-CN' | 'en-US' = 'zh-CN',
  path = '/oauth-clients',
) {
  window.history.replaceState(null, '', path);
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  root.render(
    <ConsoleLocaleProvider initialLocale={locale}>
      <BrandApplicationProvider
        resolvedBrand={platformResolvedBrandProfile}
        surface="platform"
        locale={locale}
      >
        <PlatformConsoleApp
          bootstrap={createRuntimeConfigBootstrap(() =>
            Promise.resolve({
              ok: true,
              config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
            }),
          )}
          authenticationFetch={fetch}
          realm={{}}
        />
      </BrandApplicationProvider>
    </ConsoleLocaleProvider>,
  );
}
function fixture(read: (url: URL) => Response): AuthenticationFetch {
  return async (input) => {
    await Promise.resolve();
    const url = new URL(input instanceof Request ? input.url : input);
    if (url.pathname.endsWith('/refresh'))
      return Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'fixture-token',
        tokenType: 'Bearer',
        expiresIn: 120,
      });
    return read(url);
  };
}
it('filters, pages and navigates to authoritative OAuth Client details with keyboard focus', async () => {
  const queries: URL[] = [];
  mount(
    fixture((url) => {
      if (url.pathname.endsWith('/oauth-clients')) {
        queries.push(url);
        const next = url.searchParams.has('cursor');
        return Response.json({
          items: [{ ...detail, displayName: next ? 'Second worker' : 'IAM worker' }],
          nextCursor: next ? null : 'opaque-next',
          hasMore: !next,
        });
      }
      return Response.json(detail);
    }),
  );
  await expect.element(page.getByRole('cell', { name: 'IAM worker', exact: true })).toBeVisible();
  await page.getByRole('textbox', { name: '名称', exact: true }).fill('IAM');
  await page.getByRole('combobox', { name: '类型', exact: true }).click();
  await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}');
  await page.getByRole('combobox', { name: '状态', exact: true }).click();
  await userEvent.keyboard('{ArrowDown}{Enter}');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => queries.at(-1)?.searchParams.get('name')).toBe('IAM');
  expect(queries.at(-1)?.searchParams.get('clientType')).toBe('RESERVED_SERVICE');
  expect(queries.at(-1)?.searchParams.get('status')).toBe('ACTIVE');
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect
    .element(page.getByRole('cell', { name: 'Second worker', exact: true }))
    .toBeVisible();
  expect(queries.at(-1)?.searchParams.get('cursor')).toBe('opaque-next');
  await page.getByRole('button', { name: '上一页', exact: true }).click();
  await expect.element(page.getByRole('cell', { name: 'IAM worker', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '查看详情', exact: true }).click();
  await expect
    .element(page.getByRole('heading', { name: 'OAuth Client 详情', exact: true }))
    .toBeVisible();
  await expect
    .element(page.getByText('tenant-access:membership:read', { exact: true }))
    .toBeVisible();
  expect(document.activeElement?.id).toBe('oauth-page-title');
  expect((await auditAccessibility(document.body)).violations).toEqual([]);
});
for (const status of [403, 503])
  it(`shows ${String(status)} without a fabricated empty list and retries`, async () => {
    let failed = true;
    mount(
      fixture(() =>
        failed
          ? Response.json(
              {
                type:
                  status === 403
                    ? 'urn:saasforge:problem:platform-admin-required'
                    : 'urn:saasforge:problem:upstream-unavailable',
                detail: 'Read unavailable',
                traceId: '0123456789abcdef0123456789abcdef',
                title: 'Unavailable',
                status,
                code: status === 403 ? 'PLATFORM_ADMIN_REQUIRED' : 'UPSTREAM_UNAVAILABLE',
              },
              { status, headers: { 'Content-Type': 'application/problem+json' } },
            )
          : Response.json({ items: [detail], nextCursor: null, hasMore: false }),
      ),
    );
    await expect
      .element(
        page.getByText(status === 403 ? '当前身份没有平台管理员权限。' : '无法读取 OAuth Client', {
          exact: true,
        }),
      )
      .toBeVisible();
    await expect
      .element(page.getByText('没有符合条件的 OAuth Client。', { exact: true }))
      .not.toBeInTheDocument();
    failed = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await expect.element(page.getByRole('cell', { name: 'IAM worker', exact: true })).toBeVisible();
  });
it('loads an English detail route directly and reloads authoritative fields', async () => {
  let reads = 0;
  mount(
    fixture(() =>
      Response.json({ ...detail, displayName: ++reads === 1 ? 'IAM worker' : 'Updated worker' }),
    ),
    'en-US',
    `/oauth-clients/${id}`,
  );
  await expect.element(page.getByText('IAM worker', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Reload', exact: true }).click();
  await expect.element(page.getByText('Updated worker', { exact: true })).toBeVisible();
  await expect.element(page.getByText('Reserved service', { exact: true })).toBeVisible();
  expect(document.body.textContent).not.toContain('fixture-token');
  expect((await auditAccessibility(document.body)).violations).toEqual([]);
});

import { auditAccessibility } from '../shared/design-system/browser-test/accessibility-audit';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { TenantUiFixture } from './tenant-ui.fixture';

let root: Root | undefined;
let container: HTMLDivElement | undefined;
afterEach(() => {
  root?.unmount();
  container?.remove();
  vi.restoreAllMocks();
});

function mount(locale: 'zh-CN' | 'en-US' = 'zh-CN', path = '/tenants') {
  window.history.replaceState(null, '', path);
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  root.render(<TenantUiFixture locale={locale} />);
}

describe('A 分区列表的正式 Console 浏览器缝', () => {
  it('英文初始化成功详情在 320px 下无横向溢出', async () => {
    await page.viewport(1440, 900);
    mount('en-US', '/tenants/019535d9-0000-7000-8000-000000000001');
    await expect.element(page.getByText('Initialization completed', { exact: true })).toBeVisible();
    await page.viewport(320, 900);
    await expect.poll(() => document.documentElement.scrollWidth).toBeLessThanOrEqual(320);
  });
  it('保留查询和详情导航，唯一语言控件移入顶栏', async () => {
    await page.viewport(1440, 900);
    mount();
    await expect
      .element(page.getByRole('heading', { name: 'Tenant 列表', exact: true }))
      .toBeVisible();
    await expect.element(page.getByRole('cell', { name: '云杉协作', exact: true })).toBeVisible();
    expect(document.querySelectorAll('#console-locale')).toHaveLength(1);
    expect(document.querySelector('.sf-application-header #console-locale')).not.toBeNull();
    await page.getByRole('textbox', { name: '名称', exact: true }).fill('云杉');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await expect
      .element(page.getByRole('cell', { name: '星河科技', exact: true }))
      .not.toBeInTheDocument();
    await page.getByRole('button', { name: '查看详情', exact: true }).click();
    await expect
      .element(page.getByRole('heading', { name: 'Tenant 详情', exact: true }))
      .toBeVisible();
    expect(document.activeElement?.id).toBe('tenant-page-title');
    expect(document.querySelectorAll('main')).toHaveLength(1);
  });

  it('320px 下导航抽屉约束焦点，Esc 恢复触发点，页面无横向溢出', async () => {
    await page.viewport(320, 800);
    mount();
    await expect.element(page.getByRole('cell', { name: '云杉协作', exact: true })).toBeVisible();
    const open = page.getByRole('button', { name: '打开导航', exact: true });
    await open.click();
    const dialog = page.getByRole('dialog');
    await expect.element(dialog).toBeVisible();
    await userEvent.keyboard('{Shift>}{Tab}{/Shift}');
    expect(dialog.element().contains(document.activeElement)).toBe(true);
    const dialogElement = dialog.element();
    await userEvent.keyboard('{Escape}');
    await expect.element(dialogElement).not.toBeVisible();
    expect(document.activeElement).toBe(open.element());
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(320);
  });

  for (const scheme of ['light', 'dark'] as const) {
    it(`${scheme} 样板页面保持语义与对比度`, async () => {
      const original = window.matchMedia.bind(window);
      vi.spyOn(window, 'matchMedia').mockImplementation((query) =>
        query === '(prefers-color-scheme: dark)'
          ? ({
              media: query,
              onchange: null,
              dispatchEvent: () => true,
              addListener: () => undefined,
              removeListener: () => undefined,
              matches: scheme === 'dark',
              addEventListener: () => undefined,
              removeEventListener: () => undefined,
            } as MediaQueryList)
          : original(query),
      );
      await page.viewport(1440, 900);
      mount();
      await expect.element(page.getByRole('cell', { name: '云杉协作', exact: true })).toBeVisible();
      const result = await auditAccessibility(document.body);
      expect(
        result.violations.map((item) => ({
          id: item.id,
          nodes: item.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
        })),
      ).toEqual([]);
      if (import.meta.env.SF_VISUAL_SNAPSHOTS !== 'false')
        await expect.element(page.getByRole('main')).toMatchScreenshot(`tenant-a-${scheme}`);
    });
  }
});

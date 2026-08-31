import axe from 'axe-core';
import type { ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';

import { ApplicationFatalError, DesignSystemProvider } from '../src';
import {
  DesignSystemShowcase,
  LayoutShowcase,
  SplitLayoutShowcase,
  ThemeLocaleMatrix,
} from '../showcase/main';

let renderedRoot: Root | undefined;
let renderedContainer: HTMLDivElement | undefined;

afterEach(() => {
  renderedRoot?.unmount();
  renderedContainer?.remove();
  renderedRoot = undefined;
  renderedContainer = undefined;
});

function render(ui: ReactNode) {
  renderedContainer = document.createElement('div');
  document.body.append(renderedContainer);
  renderedRoot = createRoot(renderedContainer);
  flushSync(() => {
    renderedRoot?.render(ui);
  });
}

function rerender(ui: ReactNode) {
  flushSync(() => {
    renderedRoot?.render(ui);
  });
}

function renderedGridColumns(itemTestId: string) {
  const grid = page.getByTestId(itemTestId).element().parentElement;
  if (grid === null) {
    throw new Error(`栅格项 ${itemTestId} 缺少布局容器。`);
  }
  return getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length;
}

function documentOrder(first: Element, second: Element) {
  return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING);
}

async function waitForLayout() {
  await new Promise<void>((resolve) => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        resolve();
      });
    });
  });
}

describe('Design System 真实浏览器展示矩阵', () => {
  it.skipIf(import.meta.env.SF_VISUAL_SNAPSHOTS === 'false')(
    '固定关键稳定状态的五个验收视口',
    async () => {
      render(
        <DesignSystemProvider>
          <ThemeLocaleMatrix />
        </DesignSystemProvider>,
      );
      const matrix = page.getByTestId('theme-locale-matrix');

      for (const width of [1440, 1280, 768, 390, 360]) {
        await page.viewport(width, width <= 768 ? 3200 : 1100);
        await expect(matrix).toMatchScreenshot(`theme-locale-matrix-${String(width)}`);
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
      }
    },
  );

  it.skipIf(import.meta.env.SF_VISUAL_SNAPSHOTS === 'false')(
    '固定标准、全宽与窄屏栅格的稳定边界',
    async () => {
      await page.viewport(1440, 1200);
      render(
        <DesignSystemProvider>
          <LayoutShowcase width="standard" />
        </DesignSystemProvider>,
      );
      const main = page.getByRole('main');
      await expect(main).toMatchScreenshot('layout-standard-1440');

      rerender(
        <DesignSystemProvider>
          <LayoutShowcase width="wide" />
        </DesignSystemProvider>,
      );
      await expect(main).toMatchScreenshot('layout-wide-1440');

      await page.viewport(390, 1600);
      await expect(main).toMatchScreenshot('layout-wide-390');
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(390);
    },
  );

  it('按组件可用空间自动换列并在 320 CSS px 保持单列与可见焦点', async () => {
    render(
      <DesignSystemProvider>
        <LayoutShowcase width="wide" />
      </DesignSystemProvider>,
    );

    const expectations = [
      { width: 1440, content: 3, statistics: 4 },
      { width: 1280, content: 3, statistics: 4 },
      { width: 768, content: 2, statistics: 3 },
      { width: 390, content: 1, statistics: 1 },
      { width: 360, content: 1, statistics: 1 },
      { width: 320, content: 1, statistics: 1 },
    ] as const;

    for (const expected of expectations) {
      await page.viewport(expected.width, 1600);
      expect(renderedGridColumns('content-grid-item-1')).toBe(expected.content);
      expect(renderedGridColumns('statistics-grid-item-1')).toBe(expected.statistics);
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(expected.width);
    }

    expect(document.querySelector('[role="grid"]')).toBeNull();
    const firstAction = page.getByRole('button', { name: '查看成员目录' });
    firstAction.element().focus();
    expect(getComputedStyle(firstAction.element()).outlineStyle).not.toBe('none');
    const actionBounds = firstAction.element().getBoundingClientRect();
    expect(actionBounds.left).toBeGreaterThanOrEqual(0);
    expect(actionBounds.right).toBeLessThanOrEqual(320);
  });

  it.skipIf(import.meta.env.SF_VISUAL_SNAPSHOTS === 'false')(
    '固定主辅分栏桌面与窄屏稳定状态',
    async () => {
      render(
        <DesignSystemProvider>
          <SplitLayoutShowcase />
        </DesignSystemProvider>,
      );
      const main = page.getByRole('main');

      await page.viewport(1280, 900);
      await expect(main).toMatchScreenshot('split-layout-1280');

      await page.viewport(390, 1200);
      await expect(main).toMatchScreenshot('split-layout-390');
    },
  );

  it('主辅分栏在 48rem 边界保持固定顺序、可见焦点和页面无横向溢出', async () => {
    render(
      <DesignSystemProvider>
        <SplitLayoutShowcase />
      </DesignSystemProvider>,
    );

    const main = page.getByRole('main').element();
    const primary = page.getByRole('region', { name: '编辑成员资料' }).element();
    const auxiliary = page.getByRole('complementary', { name: '操作提示' }).element();
    const primaryWrapper = primary.parentElement;
    if (primaryWrapper === null) {
      throw new Error('主内容缺少分栏布局容器。');
    }
    const split = primaryWrapper.parentElement;
    if (split === null) {
      throw new Error('主内容容器缺少分栏布局。');
    }

    expect(main.querySelectorAll('main')).toHaveLength(0);
    expect(documentOrder(primaryWrapper, auxiliary)).toBe(true);
    expect(document.querySelector('[role="grid"]')).toBeNull();

    await page.viewport(1280, 900);
    expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(2);
    const gap = Number.parseFloat(getComputedStyle(split).columnGap);
    const auxiliaryWidth = auxiliary.getBoundingClientRect().width;
    expect(gap).toBe(24);
    expect(auxiliaryWidth).toBeGreaterThanOrEqual(18 * 16);
    expect(auxiliaryWidth).toBeLessThanOrEqual(24 * 16);

    const splitContainer = split.parentElement;
    if (splitContainer === null) {
      throw new Error('分栏缺少 Container Query 容器。');
    }
    splitContainer.style.inlineSize = '48rem';
    await waitForLayout();
    expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(2);
    splitContainer.style.inlineSize = 'calc(48rem - 1px)';
    await waitForLayout();
    expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(1);
    splitContainer.style.removeProperty('inline-size');

    for (const width of [390, 320]) {
      await page.viewport(width, 1200);
      expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(
        1,
      );
      expect(documentOrder(primaryWrapper, auxiliary)).toBe(true);
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
    }

    await page.viewport(320, 1200);
    const primaryAction = page.getByRole('button', { name: '保存成员资料' });
    primaryAction.element().focus();
    expect(getComputedStyle(primaryAction.element()).outlineStyle).not.toBe('none');
    await userEvent.tab();
    await expect.element(page.getByRole('button', { name: '查看角色说明' })).toHaveFocus();
    expect(documentOrder(primaryWrapper, auxiliary)).toBe(true);
  });

  it('通过自动无障碍检查并响应减少动画偏好', async () => {
    render(
      <DesignSystemProvider>
        <DesignSystemShowcase />
      </DesignSystemProvider>,
    );

    const results = await axe.run(document, {
      rules: {
        'color-contrast': { enabled: true },
        'focus-order-semantics': { enabled: true },
      },
    });
    expect(
      results.violations.map((violation) => ({
        id: violation.id,
        impact: violation.impact,
        nodes: violation.nodes.map((node) => ({
          target: node.target.join(' '),
          html: node.html,
          failure: node.failureSummary,
        })),
      })),
    ).toEqual([]);

    const primaryAction = page.getByRole('button', { name: '主要操作' }).first();
    const primaryElement = primaryAction.element();
    expect(
      Number.parseFloat(getComputedStyle(primaryElement).animationDuration),
    ).toBeLessThanOrEqual(0.00001);
    expect(
      Number.parseFloat(getComputedStyle(primaryElement).transitionDuration),
    ).toBeLessThanOrEqual(0.00001);
  });

  it('真实键盘完成按钮、菜单、多行文本和危险确认流程', async () => {
    render(
      <DesignSystemProvider>
        <DesignSystemShowcase />
      </DesignSystemProvider>,
    );

    const primaryAction = page.getByRole('button', { name: '主要操作' }).first();
    const backgroundBeforeHover = getComputedStyle(primaryAction.element()).backgroundColor;
    await userEvent.hover(primaryAction);
    expect(getComputedStyle(primaryAction.element()).backgroundColor).not.toBe(
      backgroundBeforeHover,
    );
    primaryAction.element().focus();
    expect(getComputedStyle(primaryAction.element()).outlineStyle).not.toBe('none');
    await userEvent.tab();
    await expect.element(page.getByRole('button', { name: '次要操作' }).first()).toHaveFocus();

    expect(page.getByRole('main').element().dataset.layoutWidth).toBe('standard');
    await page.getByRole('button', { name: '全宽页面' }).click();
    expect(page.getByRole('main').element().dataset.layoutWidth).toBe('wide');
    await expect.element(page.getByText('当前页面：全宽管理页面')).toBeInTheDocument();
    await page.getByRole('button', { name: '标准宽度' }).click();
    expect(page.getByRole('main').element().dataset.layoutWidth).toBe('standard');

    const saveFeedback = page.getByRole('button', { name: '保存并显示成功反馈' });
    saveFeedback.element().focus();
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByText('成员已保存，提示将自动消失。')).toBeInTheDocument();
    await page.getByRole('button', { name: '默认' }).click();
    page.getByRole('button', { name: '保存并显示成功反馈' }).element().focus();
    await userEvent.keyboard(' ');
    await expect.element(page.getByText('成员已保存，提示将自动消失。')).toBeInTheDocument();

    await page.getByRole('button', { name: '展示菜单' }).click();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('button', { name: '展示菜单' })).toHaveFocus();

    await page.getByRole('button', { name: '编辑成员资料' }).click();
    const notes = page.getByRole('textbox', { name: '备注' });
    await notes.fill('第一行');
    await notes.click();
    await userEvent.keyboard('{Enter}第二行');
    await expect.element(notes).toHaveValue('第一行\n第二行');
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('button', { name: '编辑成员资料' })).toHaveFocus();

    await page.getByRole('button', { name: '停用北辰科技' }).click();
    await expect.element(page.getByRole('button', { name: '取消' })).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByRole('dialog', { name: '停用租户' })).not.toBeInTheDocument();
    await expect.element(page.getByRole('button', { name: '停用北辰科技' })).toHaveFocus();
    await page.getByRole('button', { name: '停用北辰科技' }).click();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('button', { name: '停用北辰科技' })).toHaveFocus();
  });

  it('在 390px 与 360px 完成表单、表格和确认核心流程且页面不横向溢出', async () => {
    await page.viewport(390, 3200);
    render(
      <DesignSystemProvider>
        <DesignSystemShowcase />
      </DesignSystemProvider>,
    );
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(390);

    await page.getByRole('button', { name: '填入演示数据' }).click();
    await page.getByRole('button', { name: '保存成员' }).click();
    await expect
      .element(page.getByText('成员服务暂时不可用，输入内容已保留，请重试。'))
      .toBeInTheDocument();
    await page.getByRole('button', { name: '保存成员' }).click();
    await expect.element(page.getByText('成员资料已保存。').last()).toBeInTheDocument();

    const tenantQuery = page.getByRole('textbox', { name: '租户名称' });
    await tenantQuery.fill('北辰');
    await tenantQuery.click();
    await userEvent.keyboard('{Enter}');
    await expect
      .element(page.getByRole('cell', { name: '北辰科技', exact: true }))
      .toBeInTheDocument();

    await page.getByRole('button', { name: '停用北辰科技' }).last().click();
    await expect.element(page.getByRole('button', { name: '取消' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('button', { name: '停用北辰科技' }).last()).toHaveFocus();

    await page.viewport(360, 3200);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(360);

    await page.viewport(320, 3200);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(320);
    const table = page.getByRole('table').element();
    const tableScrollRegion = table.parentElement;
    if (tableScrollRegion === null) {
      throw new Error('数据表格缺少自身滚动区域。');
    }
    expect(getComputedStyle(tableScrollRegion).overflowX).toBe('auto');
    expect(tableScrollRegion.scrollWidth).toBeGreaterThanOrEqual(tableScrollRegion.clientWidth);
  });

  it('通过公共入口显示致命错误恢复界面且不暴露异常详情', async () => {
    let reloaded = false;

    render(
      <DesignSystemProvider>
        <ApplicationFatalError
          applicationName="Platform Console"
          onReload={() => {
            reloaded = true;
          }}
        />
      </DesignSystemProvider>,
    );

    await expect
      .element(page.getByRole('heading', { name: 'Platform Console 无法继续运行' }))
      .toBeInTheDocument();
    await expect.element(page.getByText('APPLICATION_FATAL')).toBeInTheDocument();
    await page.getByRole('button', { name: '重新加载' }).click();

    expect(reloaded).toBe(true);
  });
});

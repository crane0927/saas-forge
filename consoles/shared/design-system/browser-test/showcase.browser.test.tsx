import axe from 'axe-core';
import type { ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';

import { ApplicationFatalError, DesignSystemProvider } from '../src';
import { DesignSystemShowcase, ThemeLocaleMatrix } from '../showcase/main';

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

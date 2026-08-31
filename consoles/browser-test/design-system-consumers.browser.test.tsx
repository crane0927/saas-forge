import type { ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';

import { DesignSystemConsumerRemote } from '../business-remotes/design-system-consumer-fixture/src/remote';
import { PlatformConsoleApp } from '../platform-console/src/app';
import { createRuntimeConfigBootstrap } from '../shared/app-runtime/src';
import { DesignSystemProvider } from '../shared/design-system/src';
import { TenantConsoleShellApp } from '../tenant-console-shell/src/app';

let renderedRoot: Root | undefined;
let renderedContainer: HTMLDivElement | undefined;

afterEach(() => {
  renderedRoot?.unmount();
  renderedContainer?.remove();
  renderedRoot = undefined;
  renderedContainer = undefined;
  window.history.replaceState({}, '', '/');
});

function render(ui: ReactNode) {
  renderedContainer = document.createElement('div');
  document.body.append(renderedContainer);
  renderedRoot = createRoot(renderedContainer);
  flushSync(() => {
    renderedRoot?.render(ui);
  });
}

function readyBootstrap() {
  return createRuntimeConfigBootstrap(() =>
    Promise.resolve({
      ok: true,
      config: { schemaVersion: 1, apiBaseUrl: 'https://api.saasforge.test' },
    }),
  );
}

function renderedColumns(itemTestId: string) {
  const item = document.querySelector(`[data-testid="${itemTestId}"]`);
  const grid = item?.parentElement;
  if (grid === null || grid === undefined) {
    throw new Error(`Remote 栅格项 ${itemTestId} 缺少布局容器。`);
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

describe('三个 Design System 消费者的真实浏览器契约', () => {
  it.each([
    ['Platform Console', <PlatformConsoleApp bootstrap={readyBootstrap()} />],
    ['Tenant Console', <TenantConsoleShellApp bootstrap={readyBootstrap()} />],
  ])('%s 共享启动状态进入路由并恢复到页面标题焦点', async (_name, app) => {
    render(<DesignSystemProvider>{app}</DesignSystemProvider>);

    await expect.element(page.getByText('正在启动')).toBeInTheDocument();
    const title = page.getByRole('heading', { name: '页面不存在' });
    await expect.element(title).toBeInTheDocument();
    await expect.element(title).toHaveFocus();
    expect(document.querySelectorAll('.sf-design-system-root')).toHaveLength(1);
  });

  it('Remote 从 Shell 继承主题、完成共享表单反馈且恢复键盘焦点', async () => {
    render(
      <DesignSystemProvider forcedColorScheme="dark">
        <DesignSystemConsumerRemote />
      </DesignSystemProvider>,
    );

    const root = document.querySelector<HTMLElement>('.sf-design-system-root');
    expect(root?.dataset.colorScheme).toBe('dark');
    expect(document.querySelectorAll('.sf-design-system-root')).toHaveLength(1);
    await expect
      .element(page.getByRole('heading', { name: 'Design System Remote 消费夹具' }))
      .toBeInTheDocument();
    expect(page.getByRole('main').element().textContent.trim()).not.toBe('');

    const name = page.getByRole('textbox', { name: '显示名称' });
    await name.fill('浏览器 Remote');
    await userEvent.tab();
    const submit = page.getByRole('button', { name: '验证共享反馈' });
    await expect.element(submit).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByText('浏览器 Remote 已继承宿主主题。')).toBeInTheDocument();
  });

  it('Remote 按自身可用空间换列和堆叠，并在固定宽度保持语义、顺序与焦点', async () => {
    const consoleError = vi.spyOn(console, 'error');
    const consoleWarning = vi.spyOn(console, 'warn');
    const runtimeErrors: string[] = [];
    const recordRuntimeError = (event: ErrorEvent) => {
      runtimeErrors.push(event.message);
    };
    const recordUnhandledRejection = (event: PromiseRejectionEvent) => {
      runtimeErrors.push(String(event.reason));
    };
    window.addEventListener('error', recordRuntimeError);
    window.addEventListener('unhandledrejection', recordUnhandledRejection);

    try {
      render(
        <DesignSystemProvider>
          <DesignSystemConsumerRemote />
        </DesignSystemProvider>,
      );

      const main = page.getByRole('main').element();
      const primary = page.getByRole('region', { name: '验证共享交互' }).element();
      const auxiliary = page.getByRole('complementary', { name: 'Remote 布局说明' }).element();
      const primaryWrapper = primary.parentElement;
      if (primaryWrapper === null) {
        throw new Error('Remote 主内容缺少公共分栏布局容器。');
      }
      const split = primaryWrapper.parentElement;
      if (split === null) {
        throw new Error('Remote 主内容缺少公共分栏布局容器。');
      }

      expect(main.dataset.layoutWidth).toBe('wide');
      expect(main.querySelectorAll('main')).toHaveLength(0);
      expect(document.querySelector('[role="grid"]')).toBeNull();
      expect(documentOrder(primaryWrapper, auxiliary)).toBe(true);

      const expectations = [
        { width: 1440, content: 3, statistics: 4, split: 2 },
        { width: 1280, content: 3, statistics: 4, split: 2 },
        { width: 768, content: 2, statistics: 3, split: 1 },
        { width: 390, content: 1, statistics: 1, split: 1 },
        { width: 360, content: 1, statistics: 1, split: 1 },
        { width: 320, content: 1, statistics: 1, split: 1 },
      ] as const;

      for (const expected of expectations) {
        await page.viewport(expected.width, 1800);
        await waitForLayout();
        expect(renderedColumns('remote-content-item')).toBe(expected.content);
        expect(renderedColumns('remote-statistics-item')).toBe(expected.statistics);
        expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(
          expected.split,
        );
        expect(getComputedStyle(auxiliary).display).not.toBe('none');
        expect(documentOrder(primaryWrapper, auxiliary)).toBe(true);
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(expected.width);
      }

      await page.viewport(1440, 1800);
      const pageContent = main.querySelector<HTMLElement>('.sf-page-content');
      if (pageContent === null) {
        throw new Error('Remote 页面缺少公共内容容器。');
      }
      pageContent.style.inlineSize = '40rem';
      await waitForLayout();
      expect(renderedColumns('remote-content-item')).toBe(2);
      expect(renderedColumns('remote-statistics-item')).toBe(3);
      expect(getComputedStyle(split).gridTemplateColumns.split(' ').filter(Boolean)).toHaveLength(
        1,
      );
      pageContent.style.removeProperty('inline-size');

      for (const width of [1280, 360]) {
        await page.viewport(width, 1800);
        await waitForLayout();
        const name = page.getByRole('textbox', { name: '显示名称' });
        name.element().focus();
        await userEvent.tab();
        await expect.element(page.getByRole('button', { name: '验证共享反馈' })).toHaveFocus();
        await userEvent.tab();
        await expect.element(page.getByRole('button', { name: '查看布局说明' })).toHaveFocus();
      }

      expect(document.querySelector('vite-error-overlay')).toBeNull();
      expect(runtimeErrors).toEqual([]);
      expect(consoleError).not.toHaveBeenCalled();
      expect(consoleWarning).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('error', recordRuntimeError);
      window.removeEventListener('unhandledrejection', recordUnhandledRejection);
      consoleError.mockRestore();
      consoleWarning.mockRestore();
    }
  });

  it.skipIf(import.meta.env.SF_VISUAL_SNAPSHOTS === 'false')(
    '固定 Remote 的桌面与窄屏消费证据',
    async () => {
      render(
        <DesignSystemProvider>
          <DesignSystemConsumerRemote />
        </DesignSystemProvider>,
      );
      const remote = page.getByRole('main');

      await page.viewport(1280, 900);
      await expect(remote).toMatchScreenshot('remote-consumer-1280');
      await page.viewport(390, 1800);
      await expect(remote).toMatchScreenshot('remote-consumer-390');
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(390);
    },
  );
});

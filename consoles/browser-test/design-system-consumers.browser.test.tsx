import type { ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
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

    const name = page.getByRole('textbox', { name: '显示名称' });
    await name.fill('浏览器 Remote');
    await userEvent.tab();
    const submit = page.getByRole('button', { name: '验证共享反馈' });
    await expect.element(submit).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByText('浏览器 Remote 已继承宿主主题。')).toBeInTheDocument();
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
      await page.viewport(390, 844);
      await expect(remote).toMatchScreenshot('remote-consumer-390');
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(390);
    },
  );
});

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  ApplicationFatalError,
  ApplicationLoading,
  ConfigurationFailure,
  DesignSystemProvider,
  semanticTokens,
} from '../src';

afterEach(cleanup);

describe('Design System 启动状态', () => {
  it('通过公共 Provider 显示应用启动和部署配置加载状态', () => {
    render(
      <DesignSystemProvider>
        <ApplicationLoading applicationName="Platform Console" />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('heading', { name: '正在启动 Platform Console' })).toBeTruthy();
    expect(screen.getByLabelText('正在加载部署配置')).toBeTruthy();
    expect(screen.getByText('正在加载部署配置。')).toBeTruthy();
  });

  it('持续显示配置失败码并把重试留给用户显式触发', () => {
    const retry = vi.fn();

    render(
      <DesignSystemProvider>
        <ConfigurationFailure
          applicationName="Tenant Console"
          errorCode="CONFIG_UNAVAILABLE"
          onRetry={retry}
        />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Tenant Console 配置不可用' })).toBeTruthy();
    expect(screen.getByText('CONFIG_UNAVAILABLE')).toBeTruthy();
    expect(retry).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(retry).toHaveBeenCalledOnce();
  });

  it('显示不暴露异常详情的致命错误恢复界面', () => {
    const reload = vi.fn();

    render(
      <DesignSystemProvider>
        <ApplicationFatalError applicationName="Platform Console" onReload={reload} />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Platform Console 无法继续运行' })).toBeTruthy();
    expect(screen.getByText('APPLICATION_FATAL')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '重新加载' }));

    expect(reload).toHaveBeenCalledOnce();
  });

  it('只从公开语义 Token 提供平台主色', () => {
    expect(semanticTokens.color.platformPrimary).toBe('#2563EB');
  });
});

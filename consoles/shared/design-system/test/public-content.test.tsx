import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApplicationShell,
  Button,
  ContentPanel,
  DescriptionList,
  DesignSystemProvider,
  StatusTag,
} from '../src';

afterEach(cleanup);
describe.each(['zh-CN', 'en-US'] as const)('公共内容与操作 %s', (locale) => {
  const title = locale === 'zh-CN' ? '租户资料' : 'Tenant details';
  it('内容面板、描述列表与全部状态色保留调用方文本和语义结构', () => {
    render(
      <DesignSystemProvider locale={locale}>
        <main>
          <h1>{title}</h1>
          <ContentPanel title={title} description="Northstar">
            <DescriptionList items={[{ label: 'ID', value: 'tenant-1' }]} />
            {(['success', 'warning', 'danger', 'neutral'] as const).map((tone) => (
              <StatusTag key={tone} tone={tone}>
                {tone}
              </StatusTag>
            ))}
          </ContentPanel>
        </main>
      </DesignSystemProvider>,
    );
    expect(screen.getByRole('region', { name: title })).toBeTruthy();
    expect(screen.getByRole('term').textContent).toBe('ID');
    expect(screen.getByRole('definition').textContent).toBe('tenant-1');
    for (const tone of ['success', 'warning', 'danger', 'neutral'])
      expect(screen.getByText(tone)).toBeTruthy();
  });

  it.each(['primary', 'secondary', 'text', 'danger'] as const)(
    '%s 操作由可用到禁用和提交中，不能重复触发',
    (variant) => {
      const action = vi.fn();
      const button = (disabled: boolean, loading: boolean) => (
        <DesignSystemProvider locale={locale}>
          <Button variant={variant} disabled={disabled} loading={loading} onClick={action}>
            {title}
          </Button>
        </DesignSystemProvider>
      );
      const { rerender } = render(button(false, false));
      fireEvent.click(screen.getByRole('button', { name: title }));
      expect(action).toHaveBeenCalledOnce();
      rerender(button(true, false));
      fireEvent.click(screen.getByRole('button', { name: title }));
      rerender(button(false, true));
      fireEvent.click(
        screen.getByRole('button', { name: locale === 'zh-CN' ? '正在处理' : 'Processing' }),
      );
      expect(action).toHaveBeenCalledOnce();
    },
  );

  it('ApplicationShell 提供当前语言导航名并保留选中页面', () => {
    const navigate = vi.fn();
    render(
      <DesignSystemProvider locale={locale}>
        <ApplicationShell
          applicationName="Console"
          navigationItems={[{ href: '/', label: title, current: true }]}
          onNavigate={navigate}
        >
          <h1>{title}</h1>
        </ApplicationShell>
      </DesignSystemProvider>,
    );
    expect(
      screen.getByRole('navigation', {
        name: locale === 'zh-CN' ? 'Console 全局导航' : 'Console global navigation',
      }),
    ).toBeTruthy();
    const link = screen.getByRole('link', { name: title });
    expect(link.getAttribute('aria-current')).toBe('page');
    fireEvent.click(link);
    expect(navigate).toHaveBeenCalledWith('/');
  });
});

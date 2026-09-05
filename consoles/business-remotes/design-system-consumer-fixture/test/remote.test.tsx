import { DesignSystemProvider } from '@saas-forge/design-system';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { DesignSystemConsumerRemote } from '../src/remote';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('Design System Remote 消费夹具', () => {
  it('首次挂载使用宿主传入的 Locale', () => {
    render(
      <DesignSystemProvider locale="en-US">
        <DesignSystemConsumerRemote locale="en-US" />
      </DesignSystemProvider>,
    );

    expect(
      screen.getByRole('heading', { name: 'Design System Remote consumer fixture' }),
    ).not.toBeNull();
    expect(screen.getByRole('form', { name: 'Remote consumption verification' })).not.toBeNull();
    expect(screen.getByRole('complementary', { name: 'Remote layout notes' })).not.toBeNull();
  });

  it('由宿主传入 Locale、安装唯一 Provider 并在语言更新时保留操作状态', () => {
    const { container, rerender } = render(
      <DesignSystemProvider forcedColorScheme="dark" locale="zh-CN">
        <DesignSystemConsumerRemote locale="zh-CN" />
      </DesignSystemProvider>,
    );

    expect(container.querySelectorAll('.sf-design-system-root')).toHaveLength(1);
    expect(
      container.querySelector('.sf-design-system-root')?.getAttribute('data-color-scheme'),
    ).toBe('dark');
    expect(screen.getByRole('main').dataset.layoutWidth).toBe('wide');
    expect(container.querySelectorAll('[data-layout-intent="content"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-layout-intent="compact-statistics"]')).toHaveLength(1);
    expect(screen.getAllByTestId('remote-content-item')).toHaveLength(3);
    expect(screen.getAllByTestId('remote-statistics-item')).toHaveLength(4);
    expect(screen.getByRole('complementary', { name: 'Remote 布局说明' })).not.toBeNull();
    expect(screen.queryByRole('grid')).toBeNull();
    fireEvent.change(screen.getByRole('textbox', { name: '显示名称' }), {
      target: { value: '合同 Remote' },
    });
    fireEvent.click(screen.getByRole('button', { name: '验证共享反馈' }));
    expect(screen.getByText('合同 Remote 已继承宿主主题。')).not.toBeNull();

    const input = screen.getByRole('textbox', { name: '显示名称' });
    input.focus();
    const main = screen.getByRole('main');
    rerender(
      <DesignSystemProvider forcedColorScheme="dark" locale="en-US">
        <DesignSystemConsumerRemote locale="en-US" />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('main')).toBe(main);
    const translatedInput = screen.getByRole<HTMLInputElement>('textbox', {
      name: 'Display name',
    });
    expect(translatedInput.value).toBe('合同 Remote');
    expect(screen.getByText('合同 Remote inherited the host theme.')).not.toBeNull();
    expect(document.activeElement).toBe(translatedInput);
  });
});

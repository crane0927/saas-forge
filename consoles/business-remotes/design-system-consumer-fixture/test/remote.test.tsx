import { DesignSystemProvider } from '@saas-forge/design-system';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { DesignSystemConsumerRemote } from '../src/remote';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('Design System Remote 消费夹具', () => {
  it('由宿主安装唯一 Provider 并使用共享表单和反馈', () => {
    const { container } = render(
      <DesignSystemProvider forcedColorScheme="dark">
        <DesignSystemConsumerRemote />
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
  });
});

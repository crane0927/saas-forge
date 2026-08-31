import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  DesignSystemProvider,
  FormLayout,
  FormRow,
  PageLayout,
  PageTitle,
  ResponsiveGrid,
} from '../src';

afterEach(cleanup);

describe('Design System 页面宽度与响应式内容栅格', () => {
  it('PageLayout 未选择新能力时保持标准宽度，且可显式选择全宽', () => {
    const { rerender } = render(
      <DesignSystemProvider>
        <PageLayout title={<PageTitle>标准页面</PageTitle>}>
          <p>标准内容</p>
        </PageLayout>
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('main').dataset.layoutWidth).toBe('standard');
    expect(screen.getByText('标准内容')).toBeTruthy();

    rerender(
      <DesignSystemProvider>
        <PageLayout width="wide" title={<PageTitle>全宽页面</PageTitle>}>
          <p>全宽内容</p>
        </PageLayout>
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('main').dataset.layoutWidth).toBe('wide');
    expect(screen.getByText('全宽内容')).toBeTruthy();
  });

  it('两种栅格意图保留消费者提供的内容语义且不引入交互式 grid 角色', () => {
    render(
      <DesignSystemProvider>
        <ResponsiveGrid intent="content">
          <article>普通内容</article>
        </ResponsiveGrid>
        <ResponsiveGrid intent="compact-statistics">
          <dl>
            <dt>活跃租户</dt>
            <dd>28</dd>
          </dl>
        </ResponsiveGrid>
      </DesignSystemProvider>,
    );

    expect(screen.getByText('普通内容').closest('article')).toBeTruthy();
    expect(screen.getByText('活跃租户').closest('dl')).toBeTruthy();
    expect(document.querySelector('[data-layout-intent="content"]')).toBeTruthy();
    expect(document.querySelector('[data-layout-intent="compact-statistics"]')).toBeTruthy();
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('既有表单继续使用 FormLayout 与受控双字段行', () => {
    render(
      <DesignSystemProvider>
        <FormLayout ariaLabel="兼容表单" onSubmit={() => undefined}>
          <FormRow>
            <label>
              名称
              <input />
            </label>
            <label>
              编码
              <input />
            </label>
          </FormRow>
        </FormLayout>
      </DesignSystemProvider>,
    );

    const form = screen.getByRole('form', { name: '兼容表单' });
    expect(form.children).toHaveLength(1);
    expect(form.firstElementChild?.children).toHaveLength(2);
  });
});

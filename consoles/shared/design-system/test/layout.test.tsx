import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  DesignSystemProvider,
  FormLayout,
  FormRow,
  PageLayout,
  PageTitle,
  ResponsiveGrid,
  SplitLayout,
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

describe('Design System 语义化主辅分栏', () => {
  it('保持主内容在前，并通过直接名称标识辅助栏', () => {
    render(
      <DesignSystemProvider>
        <PageLayout title={<PageTitle>成员详情</PageTitle>}>
          <SplitLayout
            primary={<section aria-label="成员资料">主内容</section>}
            auxiliary={<p>辅助内容</p>}
            auxiliaryLabel="成员辅助信息"
          />
        </PageLayout>
      </DesignSystemProvider>,
    );

    const main = screen.getByRole('main');
    const primary = screen.getByRole('region', { name: '成员资料' });
    const auxiliary = screen.getByRole('complementary', { name: '成员辅助信息' });

    expect(main.querySelectorAll('main')).toHaveLength(0);
    expect(primary.compareDocumentPosition(auxiliary) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(
      0,
    );
    expect(auxiliary.textContent).toBe('辅助内容');
  });

  it('可以通过消费者提供的标题关联辅助栏名称', () => {
    render(
      <DesignSystemProvider>
        <SplitLayout
          primary={<p>主内容</p>}
          auxiliary={
            <section>
              <h2 id="activity-title">最近活动</h2>
              <p>今天登录</p>
            </section>
          }
          auxiliaryLabelledBy="activity-title"
        />
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('complementary', { name: '最近活动' })).toBeTruthy();
  });

  it('默认使用普通容器承载主内容，且不产生额外键盘交互', () => {
    render(
      <DesignSystemProvider>
        <SplitLayout
          primary={<button type="button">主操作</button>}
          auxiliary={<a href="#details">查看详情</a>}
          auxiliaryLabel="辅助信息"
        />
      </DesignSystemProvider>,
    );

    expect(screen.queryAllByRole('main')).toHaveLength(0);
    expect(screen.getByRole('button', { name: '主操作' }).parentElement?.tagName).toBe('DIV');
    expect(document.querySelector('[role="grid"]')).toBeNull();
    expect(document.querySelector('[tabindex]')).toBeNull();
  });

  it('拒绝缺少辅助栏可访问名称的非法调用', () => {
    const invalidProps = {
      primary: <p>主内容</p>,
      auxiliary: <p>辅助内容</p>,
    } as unknown as Parameters<typeof SplitLayout>[0];

    expect(() =>
      render(
        <DesignSystemProvider>
          <SplitLayout {...invalidProps} />
        </DesignSystemProvider>,
      ),
    ).toThrow('SplitLayout 辅助栏必须且只能提供一种可访问名称。');
  });
});

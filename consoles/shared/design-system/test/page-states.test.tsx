import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  Button,
  DesignIcon,
  DesignSystemProvider,
  EmptyDataState,
  FilteredEmptyState,
  InitialContentLoading,
  Link,
  LoadFailureState,
  NotFoundState,
  PageLayout,
  PageTitle,
  PersistentError,
  RefreshingContent,
  SuccessFeedback,
  WarningFeedback,
} from '../src';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Design System 页面结构与状态', () => {
  it('只通过公共入口提供页面结构、按钮、链接和可访问图标', () => {
    const save = vi.fn();
    render(
      <DesignSystemProvider>
        <PageLayout
          title={
            <PageTitle
              description="管理当前组织的成员。"
              actions={<Button onClick={save}>新增成员</Button>}
            >
              成员管理
            </PageTitle>
          }
        >
          <Link href="/help">查看帮助</Link>
          <DesignIcon name="warning" label="警告" />
        </PageLayout>
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('main')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: '成员管理' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '查看帮助' }).getAttribute('href')).toBe('/help');
    expect(screen.getByRole('img', { name: '警告' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '新增成员' }));
    expect(save).toHaveBeenCalledOnce();
  });

  it('成功反馈自动消失，警告持续可见', () => {
    vi.useFakeTimers();
    const dismissed = vi.fn();
    render(
      <DesignSystemProvider>
        <SuccessFeedback message="成员已保存" durationMs={1000} onDismiss={dismissed} />
        <WarningFeedback title="配额即将用尽">剩余 5 个席位。</WarningFeedback>
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('status').textContent).toContain('成员已保存');
    expect(screen.getByText('配额即将用尽')).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(screen.queryByText('成员已保存')).toBeNull();
    expect(dismissed).toHaveBeenCalledOnce();
    expect(screen.getByText('配额即将用尽')).toBeTruthy();
  });

  it('需要处理的错误保持显示，直到执行恢复动作或主动关闭', () => {
    render(<PersistentErrorHarness />);

    expect(screen.getByRole('alert').textContent).toContain('成员保存失败');
    fireEvent.click(screen.getByRole('button', { name: '重试保存' }));
    expect(screen.getByText('重试次数：1')).toBeTruthy();
    expect(screen.getByRole('alert')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '关闭错误提示' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('首次加载、局部更新和按钮提交使用不同作用范围', () => {
    const submit = vi.fn();
    const { rerender } = render(
      <DesignSystemProvider>
        <InitialContentLoading />
        <RefreshingContent refreshing>
          <p>保留的成员列表</p>
        </RefreshingContent>
        <Button loading loadingLabel="正在保存成员" onClick={submit}>
          保存
        </Button>
      </DesignSystemProvider>,
    );

    expect(screen.getByLabelText('正在加载页面内容').getAttribute('aria-busy')).toBe('true');
    expect(screen.getByRole('status', { name: '' }).textContent).toContain('正在更新当前内容');
    expect(screen.getByText('保留的成员列表')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '正在保存成员' }));
    expect(submit).not.toHaveBeenCalled();

    rerender(
      <DesignSystemProvider>
        <RefreshingContent refreshing={false}>
          <p>已更新的成员列表</p>
        </RefreshingContent>
      </DesignSystemProvider>,
    );
    expect(screen.getByText('已更新的成员列表')).toBeTruthy();
    expect(screen.queryByText('正在更新当前内容')).toBeNull();
  });

  it('区分无数据、筛选无结果、加载失败和 404，并提供对应恢复动作', () => {
    render(<RecoveryStateHarness />);

    expect(screen.getByRole('status', { name: '暂无租户' })).toBeTruthy();
    expect(screen.getByRole('status', { name: '未找到匹配结果' })).toBeTruthy();
    expect(screen.getByRole('alert', { name: '加载失败' })).toBeTruthy();
    expect(screen.getByRole('status', { name: '页面不存在' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(screen.getByText('重试条件：active')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '重置筛选条件' }));
    expect(screen.getByText('筛选条件：全部')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '返回工作台' }));
    expect(screen.getByText('已返回：是')).toBeTruthy();
  });
});

function PersistentErrorHarness() {
  const [visible, setVisible] = useState(true);
  const [retryCount, setRetryCount] = useState(0);
  return (
    <DesignSystemProvider>
      <p>重试次数：{retryCount}</p>
      {visible ? (
        <PersistentError
          title="成员保存失败"
          action={{
            label: '重试保存',
            onAction: () => {
              setRetryCount((count) => count + 1);
            },
          }}
          onClose={() => {
            setVisible(false);
          }}
        >
          请检查成员信息后重试。
        </PersistentError>
      ) : null}
    </DesignSystemProvider>
  );
}

function RecoveryStateHarness() {
  const [filter, setFilter] = useState('active');
  const [retriedFilter, setRetriedFilter] = useState('尚未重试');
  const [returned, setReturned] = useState(false);
  return (
    <DesignSystemProvider>
      <p>筛选条件：{filter === 'active' ? '启用' : '全部'}</p>
      <p>重试条件：{retriedFilter}</p>
      <p>已返回：{returned ? '是' : '否'}</p>
      <EmptyDataState title="暂无租户" description="创建第一个租户后将在这里显示。" />
      <FilteredEmptyState
        onReset={() => {
          setFilter('all');
        }}
      />
      <LoadFailureState
        onRetry={() => {
          setRetriedFilter(filter);
        }}
      />
      <NotFoundState
        onReturn={() => {
          setReturned(true);
        }}
        returnLabel="返回工作台"
      />
    </DesignSystemProvider>
  );
}

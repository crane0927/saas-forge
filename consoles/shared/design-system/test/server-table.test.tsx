import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DesignSystemProvider,
  RecoverableDangerDialog,
  ServerTable,
  TextField,
  type ServerTableRequest,
  type ServerTableSort,
} from '../src';

afterEach(cleanup);

interface TenantRow {
  readonly id: string;
  readonly name: string;
  readonly status: string;
}

const rows: readonly TenantRow[] = [
  { id: 'tenant-1', name: '北辰科技', status: '启用' },
  { id: 'tenant-2', name: '云帆数据', status: '停用' },
];

describe('Design System 服务端表格', () => {
  it('输入查询条件不连续请求，Enter 一次查询，重置恢复默认条件并重新查询', () => {
    const query = vi.fn();
    const reset = vi.fn();
    render(<TableHarness onQuery={query} onReset={reset} />);

    const input = screen.getByRole('textbox', { name: '租户名称' });
    fireEvent.change(input, { target: { value: '北辰' } });
    expect(query).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    expect(query).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: '重置' }));
    expect(reset).toHaveBeenCalledOnce();
    expect(input.getAttribute('value')).toBe('');
  });

  it('只允许明确列排序，并区分分页与排序事件', () => {
    const change = vi.fn<(request: ServerTableRequest, reason: 'paginate' | 'sort') => void>();
    render(<TableHarness onTableChange={change} total={4} />);

    const statusHeader = screen.getByRole('columnheader', { name: '状态' });
    expect(statusHeader.getAttribute('aria-sort')).toBeNull();

    fireEvent.click(screen.getByRole('checkbox', { name: '选择 北辰科技' }));
    expect(screen.getByRole('status').textContent).toContain('已选择 1 项');
    fireEvent.click(screen.getByTitle('2'));
    expect(change).toHaveBeenLastCalledWith({ page: 2, pageSize: 2, sort: undefined }, 'paginate');
    expect(screen.getByRole('status').textContent).toContain('已清除当前页选择');

    fireEvent.click(screen.getByRole('columnheader', { name: /租户名称/ }));
    expect(change).toHaveBeenLastCalledWith(
      { page: 1, pageSize: 2, sort: { field: 'name', direction: 'asc' } },
      'sort',
    );
  });

  // Ant Design Table 的首次列测量与 Dropdown Portal 同属此集成用例；JDK CI 下需要保留足够时间，其他用例仍使用默认门禁。
  it('每行操作超过三个时只显示前两个和更多，并可进入危险确认', async () => {
    render(<ActionHarness />);

    const firstRow = screen.getByRole('row', { name: /北辰科技/ });
    expect(within(firstRow).getByRole('button', { name: '查看' })).toBeTruthy();
    expect(within(firstRow).getByRole('button', { name: '编辑' })).toBeTruthy();
    expect(within(firstRow).queryByRole('button', { name: '停用' })).toBeNull();
    fireEvent.click(within(firstRow).getByRole('button', { name: '更多' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '停用' }));

    expect(await screen.findByRole('dialog', { name: '停用租户' })).toBeTruthy();
    expect(
      within(screen.getByRole('dialog', { name: '停用租户' })).getByText('北辰科技'),
    ).toBeTruthy();
  }, 15_000);

  it('区分首次加载、更新、无数据、筛选无结果和加载失败', () => {
    const retry = vi.fn();
    const reset = vi.fn();
    const { rerender } = render(
      <StateTable initialLoading onRetry={retry} onReset={reset} rows={[]} />,
    );
    expect(screen.getByLabelText('正在加载租户列表')).toBeTruthy();

    rerender(
      <StateTable loadError="租户服务暂时不可用。" onRetry={retry} onReset={reset} rows={[]} />,
    );
    expect(screen.getByRole('alert', { name: '加载失败' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(retry).toHaveBeenCalledOnce();

    rerender(<StateTable onRetry={retry} onReset={reset} rows={[]} />);
    expect(screen.getByRole('status', { name: '暂无数据' })).toBeTruthy();

    rerender(<StateTable filtered onRetry={retry} onReset={reset} rows={[]} />);
    expect(screen.getByRole('status', { name: '未找到匹配结果' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '重置筛选条件' }));
    expect(reset).toHaveBeenCalledOnce();

    rerender(<StateTable refreshing onRetry={retry} onReset={reset} rows={rows} />);
    expect(screen.getByText('正在更新租户列表')).toBeTruthy();
    expect(screen.getByText('北辰科技')).toBeTruthy();
  });
});

function TableHarness({
  onQuery = () => undefined,
  onReset = () => undefined,
  onTableChange = () => undefined,
  total = 2,
}: {
  readonly onQuery?: () => void;
  readonly onReset?: () => void;
  readonly onTableChange?: (request: ServerTableRequest, reason: 'paginate' | 'sort') => void;
  readonly total?: number;
}) {
  const [name, setName] = useState('');
  const [sort] = useState<ServerTableSort>();
  return (
    <DesignSystemProvider>
      <ServerTable
        ariaLabel="租户列表"
        query={<TextField id="tenant-name" label="租户名称" value={name} onValueChange={setName} />}
        onQuery={onQuery}
        onReset={() => {
          setName('');
          onReset();
        }}
        rows={rows}
        rowKey={(row) => row.id}
        columns={[
          {
            key: 'name',
            title: '租户名称',
            render: (row) => row.name,
            sortable: true,
            fixed: 'left',
          },
          { key: 'status', title: '状态', render: (row) => row.status },
        ]}
        page={1}
        pageSize={2}
        total={total}
        sort={sort}
        selectionLabel={(row) => `选择 ${row.name}`}
        onTableChange={onTableChange}
      />
    </DesignSystemProvider>
  );
}

function ActionHarness() {
  const [target, setTarget] = useState<TenantRow>();
  return (
    <DesignSystemProvider>
      <ServerTable
        ariaLabel="租户操作列表"
        rows={rows}
        rowKey={(row) => row.id}
        columns={[{ key: 'name', title: '租户名称', render: (row) => row.name }]}
        actions={[
          { key: 'view', label: '查看', onAction: () => undefined },
          { key: 'edit', label: '编辑', onAction: () => undefined },
          { key: 'copy', label: '复制', onAction: () => undefined },
          { key: 'disable', label: '停用', danger: true, onAction: setTarget },
        ]}
        page={1}
        pageSize={2}
        total={2}
        onTableChange={() => undefined}
      />
      <RecoverableDangerDialog
        open={target !== undefined}
        title="停用租户"
        objectName={target?.name ?? ''}
        consequence="停用后成员暂时无法登录，管理员可以恢复。"
        actionLabel="停用租户"
        onCancel={() => {
          setTarget(undefined);
        }}
        onConfirm={() => {
          setTarget(undefined);
        }}
      />
    </DesignSystemProvider>
  );
}

function StateTable({
  rows: stateRows,
  initialLoading = false,
  refreshing = false,
  filtered = false,
  loadError,
  onRetry,
  onReset,
}: {
  readonly rows: readonly TenantRow[];
  readonly initialLoading?: boolean;
  readonly refreshing?: boolean;
  readonly filtered?: boolean;
  readonly loadError?: string;
  readonly onRetry: () => void;
  readonly onReset: () => void;
}) {
  return (
    <DesignSystemProvider>
      <ServerTable
        ariaLabel="租户列表"
        rows={stateRows}
        rowKey={(row) => row.id}
        columns={[{ key: 'name', title: '租户名称', render: (row) => row.name }]}
        page={1}
        pageSize={2}
        total={stateRows.length}
        onTableChange={() => undefined}
        initialLoading={initialLoading}
        refreshing={refreshing}
        filtered={filtered}
        loadError={loadError}
        onRetry={onRetry}
        onReset={onReset}
      />
    </DesignSystemProvider>
  );
}

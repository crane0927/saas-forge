import { Table } from 'antd';
import type { Key, ReactNode } from 'react';
import { useState } from 'react';

import { Button } from './foundation';
import { ActionMenu, type ActionMenuItem } from './overlays';
import {
  EmptyDataState,
  FilteredEmptyState,
  InitialContentLoading,
  LoadFailureState,
  RefreshingContent,
} from './page-states';

export type ServerTableSortDirection = 'asc' | 'desc';
export type ServerTableChangeReason = 'paginate' | 'sort';

export interface ServerTableSort {
  readonly field: string;
  readonly direction: ServerTableSortDirection;
}

export interface ServerTableRequest {
  readonly page: number;
  readonly pageSize: number;
  readonly sort?: ServerTableSort;
}

export interface ServerTableColumn<RecordType> {
  readonly key: string;
  readonly title: ReactNode;
  readonly render: (record: RecordType) => ReactNode;
  readonly sortable?: boolean;
  readonly fixed?: 'left';
  readonly width?: number;
}

export interface ServerTableAction<RecordType> {
  readonly key: string;
  readonly label: string;
  readonly onAction: (record: RecordType) => void;
  readonly danger?: boolean;
  readonly disabled?: boolean | ((record: RecordType) => boolean);
}

export interface ServerTableProps<RecordType> {
  readonly ariaLabel: string;
  readonly query?: ReactNode;
  readonly onQuery?: () => void;
  readonly onReset?: () => void;
  readonly queryLabel?: string;
  readonly resetLabel?: string;
  readonly rows: readonly RecordType[];
  readonly rowKey: (record: RecordType) => Key;
  readonly columns: readonly ServerTableColumn<RecordType>[];
  readonly actions?: readonly ServerTableAction<RecordType>[];
  readonly actionColumnTitle?: string;
  readonly page: number;
  readonly pageSize: number;
  readonly total: number;
  readonly sort?: ServerTableSort;
  readonly onTableChange: (request: ServerTableRequest, reason: ServerTableChangeReason) => void;
  readonly onSelectionChange?: (selectedRows: readonly RecordType[]) => void;
  readonly selectionLabel?: (record: RecordType) => string;
  readonly initialLoading?: boolean;
  readonly refreshing?: boolean;
  readonly loadError?: string;
  readonly onRetry?: () => void;
  readonly filtered?: boolean;
  readonly emptyDescription?: string;
}

/**
 * 提供服务端分页表格的完整交互边界。筛选值由调用方持有，但只有本组件触发的查询或重置才应请求服务端。
 */
export function ServerTable<RecordType>({
  ariaLabel,
  query,
  onQuery,
  onReset,
  queryLabel = '查询',
  resetLabel = '重置',
  rows,
  rowKey,
  columns,
  actions = [],
  actionColumnTitle = '操作',
  page,
  pageSize,
  total,
  sort,
  onTableChange,
  onSelectionChange,
  selectionLabel,
  initialLoading = false,
  refreshing = false,
  loadError,
  onRetry,
  filtered = false,
  emptyDescription = '当前还没有可显示的数据。',
}: ServerTableProps<RecordType>) {
  const [selectedKeys, setSelectedKeys] = useState<readonly Key[]>([]);
  const [selectionMessage, setSelectionMessage] = useState('当前页尚未选择记录。');

  const updateSelection = (keys: readonly Key[], selectedRows: readonly RecordType[]) => {
    setSelectedKeys(keys);
    setSelectionMessage(
      keys.length === 0 ? '当前页尚未选择记录。' : `已选择 ${String(keys.length)} 项当前页记录。`,
    );
    onSelectionChange?.(selectedRows);
  };

  const clearSelection = () => {
    if (selectedKeys.length > 0) {
      setSelectionMessage('已清除当前页选择。');
    }
    setSelectedKeys([]);
    onSelectionChange?.([]);
  };

  const tableColumns: Array<{
    key: string;
    title: ReactNode;
    width?: number;
    fixed?: 'left' | 'right';
    sorter: boolean;
    sortOrder: 'ascend' | 'descend' | null;
    render: (_value: unknown, record: RecordType) => ReactNode;
  }> = columns.map((column) => ({
    key: column.key,
    title: column.title,
    width: column.width,
    fixed: column.fixed,
    sorter: column.sortable === true,
    sortOrder:
      sort?.field === column.key
        ? sort.direction === 'asc'
          ? ('ascend' as const)
          : ('descend' as const)
        : null,
    render: (_value: unknown, record: RecordType) => column.render(record),
  }));

  if (actions.length > 0) {
    tableColumns.push({
      key: '__actions',
      title: actionColumnTitle,
      width: actions.length > 3 ? 220 : Math.max(120, actions.length * 72),
      fixed: 'right',
      sorter: false,
      sortOrder: null,
      render: (_value: unknown, record: RecordType) => (
        <RowActions record={record} actions={actions} />
      ),
    });
  }

  const queryPanel =
    query === undefined ? null : (
      <form
        className="sf-table-query"
        aria-label={`${ariaLabel}查询条件`}
        onKeyDown={(event) => {
          if (
            event.key !== 'Enter' ||
            !(event.target instanceof HTMLInputElement) ||
            event.target.getAttribute('role') === 'combobox'
          ) {
            return;
          }
          event.preventDefault();
          clearSelection();
          onQuery?.();
        }}
        onSubmit={(event) => {
          event.preventDefault();
          clearSelection();
          onQuery?.();
        }}
      >
        <div className="sf-table-query-fields">{query}</div>
        <div className="sf-table-query-actions">
          <Button type="submit" variant="primary">
            {queryLabel}
          </Button>
          <Button
            onClick={() => {
              clearSelection();
              onReset?.();
            }}
          >
            {resetLabel}
          </Button>
        </div>
      </form>
    );

  let content: ReactNode;
  if (initialLoading) {
    content = <InitialContentLoading label={`正在加载${ariaLabel}`} />;
  } else if (loadError !== undefined) {
    content = (
      <LoadFailureState
        description={loadError}
        onRetry={() => {
          onRetry?.();
        }}
      />
    );
  } else if (rows.length === 0) {
    content = filtered ? (
      <FilteredEmptyState
        onReset={() => {
          clearSelection();
          onReset?.();
        }}
      />
    ) : (
      <EmptyDataState description={emptyDescription} />
    );
  } else {
    content = (
      <RefreshingContent refreshing={refreshing} label={`正在更新${ariaLabel}`}>
        <Table<RecordType>
          aria-label={ariaLabel}
          dataSource={[...rows]}
          rowKey={rowKey}
          columns={tableColumns}
          tableLayout="fixed"
          scroll={{ x: 'max-content' }}
          loading={false}
          rowSelection={{
            fixed: true,
            preserveSelectedRowKeys: false,
            selectedRowKeys: [...selectedKeys],
            getCheckboxProps: (record) => ({
              'aria-label': selectionLabel?.(record) ?? '选择当前页记录',
            }),
            onChange: (keys, selectedRows) => {
              updateSelection(keys, selectedRows);
            },
          }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: false,
            showTotal: (count) => `共 ${String(count)} 项`,
          }}
          onChange={(pagination, _filters, sorter, extra) => {
            if (extra.action === 'paginate') {
              clearSelection();
              onTableChange(
                {
                  page: pagination.current ?? page,
                  pageSize: pagination.pageSize ?? pageSize,
                  sort,
                },
                'paginate',
              );
              return;
            }
            if (extra.action !== 'sort') {
              return;
            }

            const activeSorter = Array.isArray(sorter) ? sorter.at(0) : sorter;
            const nextSort =
              activeSorter?.columnKey === undefined || activeSorter.order === undefined
                ? undefined
                : {
                    field: String(activeSorter.columnKey),
                    direction:
                      activeSorter.order === 'ascend' ? ('asc' as const) : ('desc' as const),
                  };
            clearSelection();
            // Ant Design 可能为一次交互同时提供分页数据；只按 action 判定排序，避免翻页被错误重置。
            onTableChange({ page: 1, pageSize, sort: nextSort }, 'sort');
          }}
        />
      </RefreshingContent>
    );
  }

  return (
    <section className="sf-server-table" aria-label={ariaLabel}>
      {queryPanel}
      <p className="sf-table-selection-status" role="status" aria-live="polite">
        {selectionMessage}
      </p>
      {content}
    </section>
  );
}

function RowActions<RecordType>({
  record,
  actions,
}: {
  readonly record: RecordType;
  readonly actions: readonly ServerTableAction<RecordType>[];
}) {
  const visibleActions = actions.length > 3 ? actions.slice(0, 2) : actions;
  const overflowActions = actions.length > 3 ? actions.slice(2) : [];
  const menuItems: ActionMenuItem[] = overflowActions.map((action, index) => ({
    key: action.key,
    label: action.label,
    danger: action.danger,
    disabled: actionDisabled(action, record),
    separatorBefore:
      action.danger === true && (index === 0 || overflowActions[index - 1]?.danger !== true),
  }));

  return (
    <div className="sf-table-row-actions">
      {visibleActions.map((action) => (
        <Button
          key={action.key}
          variant={action.danger === true ? 'danger' : 'text'}
          disabled={actionDisabled(action, record)}
          onClick={() => {
            action.onAction(record);
          }}
        >
          {action.label}
        </Button>
      ))}
      {overflowActions.length === 0 ? null : (
        <ActionMenu
          label="更多"
          items={menuItems}
          onAction={(key) => {
            overflowActions.find((action) => action.key === key)?.onAction(record);
          }}
        />
      )}
    </div>
  );
}

function actionDisabled<RecordType>(action: ServerTableAction<RecordType>, record: RecordType) {
  return typeof action.disabled === 'function' ? action.disabled(record) : action.disabled;
}

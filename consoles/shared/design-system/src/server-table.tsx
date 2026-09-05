import { Table } from 'antd';
import { createTranslator, defineMessages } from '@saas-forge/i18n';
import type { Key, ReactNode } from 'react';
import { useState } from 'react';

import { Button } from './foundation';
import enUS from './messages/server-table/en-US.json';
import zhCN from './messages/server-table/zh-CN.json';
import { ActionMenu, type ActionMenuItem } from './overlays';
import {
  EmptyDataState,
  FilteredEmptyState,
  InitialContentLoading,
  LoadFailureState,
  RefreshingContent,
} from './page-states';
import { useDesignSystemLocale } from './theme-provider';

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

const serverTableMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

function useServerTableTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/server-table',
    locale: useDesignSystemLocale(),
    messages: serverTableMessages,
  });
}

/**
 * 提供服务端分页表格的完整交互边界。筛选值由调用方持有，但只有本组件触发的查询或重置才应请求服务端。
 */
export function ServerTable<RecordType>({
  ariaLabel,
  query,
  onQuery,
  onReset,
  queryLabel,
  resetLabel,
  rows,
  rowKey,
  columns,
  actions = [],
  actionColumnTitle,
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
  emptyDescription,
}: ServerTableProps<RecordType>) {
  const translate = useServerTableTranslator();
  const [selectedKeys, setSelectedKeys] = useState<readonly Key[]>([]);
  const [selectionAnnouncement, setSelectionAnnouncement] = useState<
    | { readonly kind: 'none' }
    | { readonly kind: 'count'; readonly count: number }
    | { readonly kind: 'cleared' }
  >({ kind: 'none' });

  const selectionMessage =
    selectionAnnouncement.kind === 'count'
      ? translate.translate('tableSelectionCount', { count: selectionAnnouncement.count })
      : translate.translate(
          selectionAnnouncement.kind === 'cleared' ? 'tableSelectionCleared' : 'tableSelectionNone',
        );

  const updateSelection = (keys: readonly Key[], selectedRows: readonly RecordType[]) => {
    setSelectedKeys(keys);
    setSelectionAnnouncement(
      keys.length === 0 ? { kind: 'none' } : { kind: 'count', count: keys.length },
    );
    onSelectionChange?.(selectedRows);
  };

  const clearSelection = () => {
    if (selectedKeys.length > 0) {
      setSelectionAnnouncement({ kind: 'cleared' });
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
      title: actionColumnTitle ?? translate.translate('tableActionColumn'),
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
        aria-label={translate.translate('tableQueryAriaLabel', { tableLabel: ariaLabel })}
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
            {queryLabel ?? translate.translate('tableQuery')}
          </Button>
          <Button
            onClick={() => {
              clearSelection();
              onReset?.();
            }}
          >
            {resetLabel ?? translate.translate('tableReset')}
          </Button>
        </div>
      </form>
    );

  let content: ReactNode;
  if (initialLoading) {
    content = (
      <InitialContentLoading
        label={translate.translate('tableInitialLoading', { tableLabel: ariaLabel })}
      />
    );
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
      <EmptyDataState
        description={emptyDescription ?? translate.translate('tableEmptyDescription')}
      />
    );
  } else {
    content = (
      <RefreshingContent
        refreshing={refreshing}
        label={translate.translate('tableRefreshing', { tableLabel: ariaLabel })}
      >
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
            // Ant Design 的固定列测量行会复制可聚焦的全选框；逐行选择保留当前页语义且避免隐藏焦点。
            hideSelectAll: true,
            columnTitle: (
              <span className="sf-visually-hidden">{translate.translate('tableSelectColumn')}</span>
            ),
            preserveSelectedRowKeys: false,
            selectedRowKeys: [...selectedKeys],
            getCheckboxProps: (record) => ({
              'aria-label': selectionLabel?.(record) ?? translate.translate('tableSelectRow'),
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
            showTotal: (count) => translate.translate('tableTotal', { count }),
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
  const translate = useServerTableTranslator();
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
          label={translate.translate('tableMoreActions')}
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

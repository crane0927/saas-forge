import { Skeleton, Spin } from 'antd';
import type { ReactNode } from 'react';

import { Button, DesignIcon, type DesignIconName } from './foundation';

export interface InitialContentLoadingProps {
  readonly label?: string;
}

export interface RefreshingContentProps {
  readonly refreshing: boolean;
  readonly children: ReactNode;
  readonly label?: string;
}

export interface EmptyDataStateProps {
  readonly title?: string;
  readonly description: string;
  readonly action?: StateAction;
}

export interface FilteredEmptyStateProps {
  readonly description?: string;
  readonly onReset: () => void;
}

export interface LoadFailureStateProps {
  readonly description?: string;
  readonly onRetry: () => void;
}

export interface NotFoundStateProps {
  readonly description?: string;
  readonly onReturn?: () => void;
  readonly returnLabel?: string;
}

export interface StateAction {
  readonly label: string;
  readonly onAction: () => void;
}

interface PageStateProps {
  readonly icon: DesignIconName;
  readonly title: string;
  readonly description: string;
  readonly action?: StateAction;
  readonly alert?: boolean;
}

export function InitialContentLoading({ label = '正在加载页面内容' }: InitialContentLoadingProps) {
  return (
    <section className="sf-initial-loading" aria-busy="true" aria-label={label}>
      <Spin aria-hidden="true" />
      <span>{label}</span>
      <Skeleton active title paragraph={{ rows: 4 }} />
    </section>
  );
}

export function RefreshingContent({
  refreshing,
  children,
  label = '正在更新当前内容',
}: RefreshingContentProps) {
  return (
    <section className="sf-refreshing-content" aria-busy={refreshing}>
      {refreshing ? (
        <div className="sf-refreshing-indicator" role="status">
          <Spin size="small" aria-hidden="true" />
          <span>{label}</span>
        </div>
      ) : null}
      {children}
    </section>
  );
}

export function EmptyDataState({ title = '暂无数据', description, action }: EmptyDataStateProps) {
  return <PageState icon="empty" title={title} description={description} action={action} />;
}

export function FilteredEmptyState({
  description = '当前筛选条件下没有匹配结果。',
  onReset,
}: FilteredEmptyStateProps) {
  return (
    <PageState
      icon="search"
      title="未找到匹配结果"
      description={description}
      action={{ label: '重置筛选条件', onAction: onReset }}
    />
  );
}

export function LoadFailureState({
  description = '内容暂时无法加载，当前条件已保留。',
  onRetry,
}: LoadFailureStateProps) {
  return (
    <PageState
      icon="reload"
      title="加载失败"
      description={description}
      action={{ label: '重试', onAction: onRetry }}
      alert
    />
  );
}

export function NotFoundState({
  description = '当前地址不存在，或页面已经移动。',
  onReturn,
  returnLabel = '返回上一页',
}: NotFoundStateProps) {
  return (
    <PageState
      icon="not-found"
      title="页面不存在"
      description={description}
      action={onReturn === undefined ? undefined : { label: returnLabel, onAction: onReturn }}
    />
  );
}

function PageState({ icon, title, description, action, alert = false }: PageStateProps) {
  return (
    <section className="sf-page-state" role={alert ? 'alert' : 'status'} aria-label={title}>
      <DesignIcon name={icon} size={28} />
      <h2>{title}</h2>
      <p>{description}</p>
      {action === undefined ? null : (
        <Button variant="primary" onClick={action.onAction}>
          {action.label}
        </Button>
      )}
    </section>
  );
}

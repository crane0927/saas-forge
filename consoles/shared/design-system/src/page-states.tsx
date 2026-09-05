import { Skeleton, Spin } from 'antd';
import { createTranslator, defineMessages } from '@saas-forge/i18n';
import type { ReactNode } from 'react';

import { Button, DesignIcon, type DesignIconName } from './foundation';
import enUS from './messages/page-states/en-US.json';
import zhCN from './messages/page-states/zh-CN.json';
import { useDesignSystemLocale } from './theme-provider';

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

const pageStateMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

function usePageStateTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/page-states',
    locale: useDesignSystemLocale(),
    messages: pageStateMessages,
  });
}

export function InitialContentLoading({ label }: InitialContentLoadingProps) {
  const translate = usePageStateTranslator();
  const visibleLabel = label ?? translate.translate('pageInitialLoading');
  return (
    <section className="sf-initial-loading" aria-busy="true" aria-label={visibleLabel}>
      <Spin aria-hidden="true" />
      <span>{visibleLabel}</span>
      <Skeleton active title paragraph={{ rows: 4 }} />
    </section>
  );
}

export function RefreshingContent({ refreshing, children, label }: RefreshingContentProps) {
  const translate = usePageStateTranslator();
  return (
    <section className="sf-refreshing-content" aria-busy={refreshing}>
      {refreshing ? (
        <div className="sf-refreshing-indicator" role="status">
          <Spin size="small" aria-hidden="true" />
          <span>{label ?? translate.translate('pageRefreshing')}</span>
        </div>
      ) : null}
      {children}
    </section>
  );
}

export function EmptyDataState({ title, description, action }: EmptyDataStateProps) {
  const translate = usePageStateTranslator();
  return (
    <PageState
      icon="empty"
      title={title ?? translate.translate('pageEmptyTitle')}
      description={description}
      action={action}
    />
  );
}

export function FilteredEmptyState({ description, onReset }: FilteredEmptyStateProps) {
  const translate = usePageStateTranslator();
  return (
    <PageState
      icon="search"
      title={translate.translate('pageFilteredTitle')}
      description={description ?? translate.translate('pageFilteredDescription')}
      action={{ label: translate.translate('pageFilteredReset'), onAction: onReset }}
    />
  );
}

export function LoadFailureState({ description, onRetry }: LoadFailureStateProps) {
  const translate = usePageStateTranslator();
  return (
    <PageState
      icon="reload"
      title={translate.translate('pageLoadFailureTitle')}
      description={description ?? translate.translate('pageLoadFailureDescription')}
      action={{ label: translate.translate('pageRetry'), onAction: onRetry }}
      alert
    />
  );
}

export function NotFoundState({ description, onReturn, returnLabel }: NotFoundStateProps) {
  const translate = usePageStateTranslator();
  return (
    <PageState
      icon="not-found"
      title={translate.translate('pageNotFoundTitle')}
      description={description ?? translate.translate('pageNotFoundDescription')}
      action={
        onReturn === undefined
          ? undefined
          : { label: returnLabel ?? translate.translate('pageNotFoundReturn'), onAction: onReturn }
      }
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

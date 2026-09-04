import { Button, Spin } from 'antd';
import { createTranslator, defineMessages, type SupportedLocale } from '@saas-forge/i18n';
import { useId } from 'react';

import enUS from './messages/en-US.json';
import zhCN from './messages/zh-CN.json';
import { useDesignSystemLocale } from './theme-provider';

interface ApplicationLoadingProps {
  readonly applicationName: string;
}

interface ConfigurationFailureProps {
  readonly applicationName: string;
  readonly errorCode: string;
  readonly onRetry: () => void;
}

export interface ApplicationFatalErrorProps {
  readonly applicationName: string;
  readonly onReload: () => void;
  /** 根错误边界可能位于唯一 Provider 外，因此由 Shell 传入最后已知 Locale。 */
  readonly locale?: SupportedLocale;
}

const bootstrapMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

export function ApplicationLoading({ applicationName }: ApplicationLoadingProps) {
  const titleId = useId();
  const translate = createTranslator({
    namespace: '@saas-forge/design-system',
    locale: useDesignSystemLocale(),
    messages: bootstrapMessages,
  });

  return (
    <main className="sf-bootstrap-surface" aria-busy="true" aria-live="polite">
      <section className="sf-bootstrap-panel" aria-labelledby={titleId}>
        <Spin size="large" aria-label={translate.translate('applicationLoadingAriaLabel')} />
        <h1 id={titleId}>{translate.translate('applicationLoadingTitle', { applicationName })}</h1>
        <p>{translate.translate('applicationLoadingDescription')}</p>
      </section>
    </main>
  );
}

export function ConfigurationFailure({
  applicationName,
  errorCode,
  onRetry,
}: ConfigurationFailureProps) {
  const titleId = useId();
  const translate = createTranslator({
    namespace: '@saas-forge/design-system',
    locale: useDesignSystemLocale(),
    messages: bootstrapMessages,
  });

  return (
    <main className="sf-bootstrap-surface">
      <section className="sf-bootstrap-panel" aria-labelledby={titleId} aria-live="assertive">
        <h1 id={titleId}>
          {translate.translate('configurationFailureTitle', { applicationName })}
        </h1>
        <p>{translate.translate('configurationFailureDescription')}</p>
        <code className="sf-bootstrap-code">{errorCode}</code>
        <Button type="primary" size="large" onClick={onRetry}>
          {translate.translate('retry')}
        </Button>
      </section>
    </main>
  );
}

/**
 * 仅展示已被 Error Boundary 隔离后的安全错误信息，不能接收或渲染原始异常详情。
 */
export function ApplicationFatalError({
  applicationName,
  onReload,
  locale: fallbackLocale,
}: ApplicationFatalErrorProps) {
  const titleId = useId();
  const contextLocale = useDesignSystemLocale();
  const locale = fallbackLocale ?? contextLocale;
  const translate = createTranslator({
    namespace: '@saas-forge/design-system',
    locale,
    messages: bootstrapMessages,
  });

  return (
    <main className="sf-bootstrap-surface">
      <section className="sf-bootstrap-panel" aria-labelledby={titleId} aria-live="assertive">
        <h1 id={titleId}>{translate.translate('fatalErrorTitle', { applicationName })}</h1>
        <p>{translate.translate('fatalErrorDescription')}</p>
        <code className="sf-bootstrap-code">APPLICATION_FATAL</code>
        <Button type="primary" size="large" onClick={onReload}>
          {translate.translate('reload')}
        </Button>
      </section>
    </main>
  );
}

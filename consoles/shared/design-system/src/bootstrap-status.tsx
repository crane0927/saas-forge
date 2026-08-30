import { Button, Spin } from 'antd';
import { useId } from 'react';

interface ApplicationLoadingProps {
  readonly applicationName: string;
}

interface ConfigurationFailureProps {
  readonly applicationName: string;
  readonly errorCode: string;
  readonly onRetry: () => void;
}

export function ApplicationLoading({ applicationName }: ApplicationLoadingProps) {
  const titleId = useId();

  return (
    <main className="sf-bootstrap-surface" aria-busy="true" aria-live="polite">
      <section className="sf-bootstrap-panel" aria-labelledby={titleId}>
        <Spin size="large" aria-label="正在加载部署配置" />
        <h1 id={titleId}>正在启动 {applicationName}</h1>
        <p>正在加载部署配置。</p>
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

  return (
    <main className="sf-bootstrap-surface">
      <section className="sf-bootstrap-panel" aria-labelledby={titleId} aria-live="assertive">
        <h1 id={titleId}>{applicationName} 配置不可用</h1>
        <p>部署配置未能通过校验。请确认配置已就绪后重试。</p>
        <code className="sf-bootstrap-code">{errorCode}</code>
        <Button type="primary" size="large" onClick={onRetry}>
          重试
        </Button>
      </section>
    </main>
  );
}

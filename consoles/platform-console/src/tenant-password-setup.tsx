import type { ConsoleApiClient, TenantAdministratorPasswordSetup } from '@saas-forge/app-runtime';
import { Button, ContentPanel, PersistentError, WarningFeedback } from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { platformMessages } from './messages';

const stateMessages = {
  NOT_APPLICABLE: 'notificationNotApplicable',
  PENDING: 'notificationPending',
  MAIL_SERVICE_ACCEPTED: 'notificationAccepted',
  PASSWORD_READY: 'notificationPasswordReady',
  ACTION_REQUIRED: 'notificationActionRequired',
} as const;

export function TenantPasswordSetupSection({
  client,
  locale,
  tenantId,
  refreshVersion,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly tenantId: string;
  readonly refreshVersion: number;
}) {
  const translator = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const t = translator.translate.bind(translator);
  const [progress, setProgress] = useState<TenantAdministratorPasswordSetup>();
  const [readProblem, setReadProblem] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [unknown, setUnknown] = useState(false);
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void client.getTenantAdministratorPasswordSetup(tenantId, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setLoading(false);
      setReadProblem(result.ok ? undefined : result.problem.code);
      if (result.ok) {
        setProgress(result.value);
        // 仅共享 Client 精确关联的原操作终态能够解除未知，其他新记录不能代替本次结果。
        if (result.value.operationState === 'COMPLETED') setUnknown(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, tenantId, refreshVersion, revision]);
  function refresh() {
    setLoading(true);
    setRevision((value) => value + 1);
  }
  function submit(recover: boolean) {
    if (
      pending.current !== null ||
      loading ||
      readProblem !== undefined ||
      progress === undefined ||
      (recover ? !progress.canContinue : !progress.canResend || unknown)
    )
      return;
    const controller = new AbortController();
    pending.current = controller;
    setSubmitting(true);
    setProblem(undefined);
    const operation = recover
      ? client.recoverTenantAdministratorPasswordSetup(progress, controller.signal)
      : client.resendTenantAdministratorPasswordSetup(tenantId, controller.signal);
    void operation.then((result) => {
      if (controller.signal.aborted) return;
      pending.current = null;
      setSubmitting(false);
      setUnknown(!result.ok);
      setProblem(result.ok ? undefined : result.problem.code);
      refresh();
    });
  }
  const disabled = loading || submitting || readProblem !== undefined;
  return (
    <ContentPanel
      title={t('notificationTitle')}
      actions={
        <Button disabled={loading || submitting} onClick={refresh}>
          {t('notificationRefresh')}
        </Button>
      }
    >
      {readProblem ? (
        <PersistentError title={t('notificationReadFailed')}>
          <p>{readProblem}</p>
        </PersistentError>
      ) : null}
      {progress === undefined && loading ? <p role="status">{t('tenantLoading')}</p> : null}
      {progress ? (
        <>
          <p role="status">{t(stateMessages[progress.state])}</p>
          <p>{t('notificationIndependent')}</p>
          {progress.state === 'MAIL_SERVICE_ACCEPTED' ? (
            <p>{t('notificationAcceptanceLimit')}</p>
          ) : null}
          {progress.canResend ? (
            <Button
              disabled={disabled || unknown}
              onClick={() => {
                submit(false);
              }}
            >
              {t('notificationResend')}
            </Button>
          ) : null}
          {progress.canContinue ? (
            <Button
              disabled={disabled}
              onClick={() => {
                submit(true);
              }}
            >
              {t('notificationContinue')}
            </Button>
          ) : null}
        </>
      ) : null}
      {unknown || progress?.operationState === 'UNKNOWN' ? (
        <WarningFeedback title={t('notificationUnknown')}>
          <p>{t('notificationUnknownHint')}</p>
          <p>{problem}</p>
        </WarningFeedback>
      ) : null}
    </ContentPanel>
  );
}

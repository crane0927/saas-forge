import type { AuthenticationProblem, ConsoleApiClient } from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  PersistentError,
  RecoverableDangerDialog,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { platformMessages } from './messages';

export function TenantLifecycleSection({
  client,
  locale,
  tenantId,
  refreshVersion,
  onChanged,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly tenantId: string;
  readonly refreshVersion: number;
  readonly onChanged?: () => void;
}) {
  const translate = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const t = translate.translate.bind(translate);
  const [read, setRead] = useState<Awaited<ReturnType<ConsoleApiClient['getTenantLifecycle']>>>();
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const [confirm, setConfirm] = useState<'SUSPEND' | 'RESUME' | 'RECOVER'>();
  const pending = useRef<AbortController | undefined>(undefined);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void client.getTenantLifecycle(tenantId, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setRead(result);
      setLoading(false);
    });
    return () => {
      controller.abort();
    };
  }, [client, tenantId, refreshVersion, revision]);
  const value = !loading && read?.ok ? read.value : undefined;
  const labels = {
    SUSPEND: 'lifecycleSuspend',
    RESUME: 'lifecycleResume',
    RECOVER: 'lifecycleRecover',
  } as const;
  async function submit(action?: 'SUSPEND' | 'RESUME' | 'RECOVER') {
    if (pending.current !== undefined || value === undefined) return;
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setConfirm(undefined);
    const result =
      action === undefined
        ? await client.continueTenantLifecycle(value, controller.signal)
        : await client.changeTenantLifecycle(tenantId, action, undefined, controller.signal);
    if (controller.signal.aborted) return;
    pending.current = undefined;
    setBusy(false);
    setProblem(result.ok ? undefined : result.problem);
    setRevision((v) => v + 1);
    onChanged?.();
  }
  return (
    <ContentPanel title={t('lifecycleTitle')}>
      {loading ? (
        <p role="status">{t('oauthLoading')}</p>
      ) : read?.ok ? (
        <p role="status">
          {t(
            read.value.state === 'PENDING'
              ? read.value.action === 'RESUME'
                ? 'lifecycleResumePending'
                : 'lifecycleSuspendPending'
              : read.value.state === 'RECOVERY_REQUIRED'
                ? 'lifecycleRecoveryRequired'
                : read.value.state === 'COMPLETED'
                  ? read.value.action === 'RESUME'
                    ? 'lifecycleResumed'
                    : 'lifecycleSuspended'
                  : read.value.state === 'RETRY_REQUIRED'
                    ? 'lifecycleRetryRequired'
                    : 'lifecycleReady',
          )}
        </p>
      ) : (
        <PersistentError title={t('lifecycleReadFailed')} />
      )}
      {value?.operationId &&
      (value.state === 'RETRY_REQUIRED' ||
        value.state === 'RECOVERY_REQUIRED' ||
        value.state === 'PENDING') ? (
        <p>{t('lifecycleReference', { reference: value.operationId })}</p>
      ) : null}
      {problem !== undefined ? (
        <PersistentError title={t('lifecycleUnconfirmed')}>
          <p>{t('lifecycleIssue', { code: problem.code, traceId: problem.traceId ?? '—' })}</p>
        </PersistentError>
      ) : null}
      {value?.canSuspend ? (
        <Button
          disabled={busy}
          onClick={() => {
            setConfirm('SUSPEND');
          }}
        >
          {t('lifecycleSuspend')}
        </Button>
      ) : null}
      {value?.canResume ? (
        <Button
          disabled={busy}
          onClick={() => {
            setConfirm('RESUME');
          }}
        >
          {t('lifecycleResume')}
        </Button>
      ) : null}
      {value?.canRecoverSuspension ? (
        <Button
          disabled={busy}
          onClick={() => {
            setConfirm('RECOVER');
          }}
        >
          {t('lifecycleRecover')}
        </Button>
      ) : null}
      {value?.canContinue ? (
        <Button disabled={busy} onClick={() => void submit()}>
          {t('lifecycleContinue')}
        </Button>
      ) : null}
      <Button
        disabled={busy || loading}
        onClick={() => {
          setRevision((v) => v + 1);
        }}
      >
        {t('oauthReload')}
      </Button>
      <RecoverableDangerDialog
        open={confirm !== undefined}
        title={t(confirm === undefined ? 'lifecycleTitle' : labels[confirm])}
        objectName={tenantId}
        consequence={t(
          confirm === 'RESUME' ? 'lifecycleResumeConsequence' : 'lifecycleSuspendConsequence',
        )}
        actionLabel={t(confirm === undefined ? 'lifecycleTitle' : labels[confirm])}
        onCancel={() => {
          setConfirm(undefined);
        }}
        onConfirm={() => {
          if (confirm !== undefined) void submit(confirm);
        }}
      />
    </ContentPanel>
  );
}

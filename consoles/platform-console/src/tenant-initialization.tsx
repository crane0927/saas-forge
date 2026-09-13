import type {
  ConsoleApiClient,
  ConsoleApiResult,
  TenantAdministratorInitialization,
} from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  DescriptionList,
  FormLayout,
  PersistentError,
  TextField,
  WarningFeedback,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { platformMessages } from './messages';

type Props = {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly tenantId: string;
  readonly subscriptionEffective: boolean;
  readonly onChanged: () => void;
  readonly onDirtyChange: (dirty: boolean) => void;
};

const stateMessages = {
  NOT_STARTED: 'initializationNotStarted',
  PROCESSING: 'initializationProcessing',
  RECOVERY_REQUIRED: 'initializationRecoveryRequired',
  COMPENSATING: 'initializationCompensating',
  RETRY_REQUIRED: 'initializationRetryRequired',
  SUCCEEDED: 'initializationSucceeded',
  FAILED: 'initializationFailed',
} as const;

export function TenantInitializationSection({
  client,
  locale,
  tenantId,
  subscriptionEffective,
  onChanged,
  onDirtyChange,
}: Props) {
  const translator = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const t = translator.translate.bind(translator);
  const [read, setRead] = useState<ConsoleApiResult<TenantAdministratorInitialization>>();
  const [readProblem, setReadProblem] = useState<string>();
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [email, setEmail] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [name, setName] = useState('');
  const [phase, setPhase] = useState<'editing' | 'submitting' | 'unknown'>('editing');
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  useEffect(() => {
    onDirtyChange(phase === 'editing' && (email !== '' || name !== ''));
  }, [phase, email, name, onDirtyChange]);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  useEffect(() => {
    const controller = new AbortController();
    void client.getTenantAdministratorInitialization(tenantId, controller.signal).then((value) => {
      if (controller.signal.aborted) return;
      setReadProblem(value.ok ? undefined : value.problem.code);
      setRead((previous) => (value.ok || previous?.ok !== true ? value : previous));
      setLoading(false);
    });
    return () => {
      controller.abort();
    };
  }, [client, tenantId, revision]);

  const progress = read?.ok ? read.value : undefined;
  const quotaKnown = subscriptionEffective;
  const canStart =
    !loading &&
    readProblem === undefined &&
    phase === 'editing' &&
    progress?.canStart === true &&
    quotaKnown;
  function refresh() {
    setLoading(true);
    setRevision((value) => value + 1);
    onChanged();
  }
  function submit(recover: boolean) {
    if (
      pending.current !== null ||
      loading ||
      readProblem !== undefined ||
      (recover ? !progress?.canContinue : !canStart)
    )
      return;
    if (
      !recover &&
      (!/^[^\s@]+@[^\s@]+$/.test(email.trim()) ||
        email.trim().length > 320 ||
        name.trim().length > 200)
    ) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    const controller = new AbortController();
    pending.current = controller;
    setPhase('submitting');
    const operation =
      recover && progress
        ? client.recoverTenantAdministratorInitialization(progress, controller.signal)
        : client.initializeTenantAdministrator({
            tenantId,
            request: {
              administratorEmail: email.trim(),
              administratorDisplayName: name.trim() || undefined,
            },
            signal: controller.signal,
          });
    void operation.then((value) => {
      if (controller.signal.aborted) return;
      pending.current = null;
      setEmail('');
      setName('');
      if (value.ok) {
        setPhase('editing');
        setProblem(undefined);
        refresh();
      } else {
        // 请求失败或取消不能证明未提交；先观察根工作流，不能立即生成新 Key。
        setPhase('unknown');
        setProblem(value.problem.code);
      }
    });
  }
  return (
    <ContentPanel
      title={t('initializationTitle')}
      actions={
        <Button disabled={loading || phase === 'submitting'} onClick={refresh}>
          {t('initializationRefresh')}
        </Button>
      }
    >
      {readProblem && read?.ok ? (
        <PersistentError title={t('initializationReadFailed')}>
          <p>{readProblem}</p>
        </PersistentError>
      ) : null}
      {read === undefined ? (
        <p role="status">{t('tenantLoading')}</p>
      ) : !read.ok ? (
        <PersistentError title={t('initializationReadFailed')}>
          <p>{read.problem.code}</p>
        </PersistentError>
      ) : (
        <>
          <p role="status">{t(stateMessages[read.value.state])}</p>
          {read.value.initialAdministratorMembershipId !== null ? (
            <>
              <DescriptionList
                items={[
                  {
                    label: t('initializationMembership'),
                    value: read.value.initialAdministratorMembershipId,
                  },
                ]}
              />
              <p>{t('initializationHistorical')}</p>
              <p>{t('initializationNotification')}</p>
            </>
          ) : null}
          {read.value.failureCode ? <p>{read.value.failureCode}</p> : null}
          {read.value.canContinue ? (
            <Button
              disabled={loading || readProblem !== undefined || phase === 'submitting'}
              onClick={() => {
                submit(true);
              }}
            >
              {t('initializationContinue')}
            </Button>
          ) : null}
          {read.value.state === 'RETRY_REQUIRED' && phase === 'unknown' ? (
            <Button
              disabled={loading}
              onClick={() => {
                setPhase('editing');
                setProblem(undefined);
              }}
            >
              {t('initializationNewAttempt')}
            </Button>
          ) : null}
          {read.value.canStart ? (
            <FormLayout
              ariaLabel={t('initializationSubmit')}
              onSubmit={(event) => {
                event.preventDefault();
                submit(false);
              }}
            >
              {!quotaKnown ? <p>{t('initializationSubscriptionRequired')}</p> : null}
              <TextField
                id="initialization-email"
                label={t('initializationEmail')}
                autoComplete="email"
                error={invalid ? t('initializationInputInvalid') : undefined}
                required
                value={email}
                onValueChange={setEmail}
                disabled={!canStart}
              />
              <TextField
                id="initialization-name"
                label={t('initializationName')}
                value={name}
                onValueChange={setName}
                disabled={!canStart}
              />
              <Button type="submit" variant="primary" disabled={!canStart || email.trim() === ''}>
                {t('initializationSubmit')}
              </Button>
            </FormLayout>
          ) : null}
        </>
      )}
      {phase === 'unknown' && progress?.state !== 'SUCCEEDED' ? (
        <WarningFeedback title={t('initializationUnknown')}>
          <p>{t('initializationUnknownHint')}</p>
          <p>{problem}</p>
        </WarningFeedback>
      ) : null}
    </ContentPanel>
  );
}

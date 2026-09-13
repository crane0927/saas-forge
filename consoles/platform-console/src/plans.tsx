import type {
  ConsoleApiClient,
  ConsoleApiResult,
  ListPlansInput,
  Plan,
  PlanStatus,
} from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  DescriptionList,
  PageLayout,
  PageTitle,
  StatusTag,
  PersistentError,
  RouteFocusAnnouncement,
  SelectField,
  ServerTable,
  TextField,
  WarningFeedback,
  FormLayout,
  UnsavedChangesDialog,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { PlanRecoveryPanel, useFormExitGuard } from '@saas-forge/react-shell';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Route, Routes, useBlocker, useLocation, useNavigate, useParams } from 'react-router';
import { platformMessages } from './messages';

type Props = { readonly client: ConsoleApiClient; readonly locale: SupportedLocale };
function translator(locale: SupportedLocale) {
  return createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
}
const statusTones = {
  DRAFT: 'warning',
  ACTIVE: 'success',
  RETIRED: 'neutral',
} as const;

const statuses = {
  DRAFT: 'planDraft',
  ACTIVE: 'planActive',
  RETIRED: 'planRetired',
} as const;

export function PlanRoutes(props: Props) {
  return (
    <Routes>
      <Route index element={<PlanList {...props} />} />
      <Route path="new" element={<PlanCreate {...props} />} />
      <Route path=":planId" element={<PlanDetails {...props} />} />
    </Routes>
  );
}

function Heading({
  title,
  description,
  actions,
}: {
  readonly title: string;
  readonly description?: ReactNode;
  readonly actions?: ReactNode;
}) {
  const location = useLocation();
  return (
    <>
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="plan-page-title"
      />
      <PageTitle headingId="plan-page-title" description={description} actions={actions}>
        {title}
      </PageTitle>
    </>
  );
}

function PlanList({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<PlanStatus | ''>('');
  const [query, setQuery] = useState<ListPlansInput>({ limit: 50 });
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Awaited<ReturnType<ConsoleApiClient['listPlans']>>>();
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    void client.listPlans({ ...query, signal: controller.signal }).then((next) => {
      if (!controller.signal.aborted) {
        setResult(next);
        setBusy(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, query, attempt]);
  function search(reset: boolean) {
    if (reset) {
      setCode('');
      setStatus('');
    }
    setCursors([undefined]);
    setBusy(true);
    setQuery({
      limit: 50,
      code: reset ? undefined : code,
      status: reset || status === '' ? undefined : status,
    });
  }
  function paginate(next: (string | undefined)[]) {
    setCursors(next);
    setBusy(true);
    setQuery({ ...query, cursor: next.at(-1) });
  }
  return (
    <PageLayout
      as="section"
      width="wide"
      title={
        <Heading
          title={t('planDefinitionsTitle')}
          description={t('planDefinitionsDescription')}
          actions={
            <Button
              variant="primary"
              onClick={() => {
                void navigate('/plans/new');
              }}
            >
              {t('planCreate')}
            </Button>
          }
        />
      }
    >
      <ServerTable<Plan>
        presentation="panel"
        title={t('planListTitle')}
        selectable={false}
        ariaLabel={t('planDefinitionsTitle')}
        rows={result?.ok ? result.value.items : []}
        rowKey={(row) => row.id}
        columns={[
          { key: 'name', title: t('planName'), render: (row) => row.code },
          { key: 'displayName', title: t('planDisplayName'), render: (row) => row.displayName },
          { key: 'limit', title: t('planLimit'), render: (row) => row.quotaLimits[0].limit },
          {
            key: 'eligibility',
            title: t('planEligibility'),
            render: (row) => t(row.quotaLimits[0].limit < 1 ? 'planLegacyZero' : 'planPositive'),
          },
          {
            key: 'status',
            title: t('planStatus'),
            render: (row) => (
              <StatusTag tone={statusTones[row.status]}>{t(statuses[row.status])}</StatusTag>
            ),
          },
        ]}
        actions={[
          {
            key: 'view',
            label: t('planView'),
            onAction: (row) => {
              void navigate(`/plans/${row.id}`);
            },
          },
        ]}
        page={cursors.length}
        pageSize={50}
        onTableChange={() => undefined}
        initialLoading={result === undefined}
        refreshing={busy && result !== undefined}
        loadError={
          result?.ok === false ? `${t('planReadFailed')}: ${result.problem.code}` : undefined
        }
        onRetry={() => {
          setBusy(true);
          setAttempt((value) => value + 1);
        }}
        filtered={Boolean(query.code || query.status)}
        emptyDescription={t('planEmpty')}
        onQuery={() => {
          search(false);
        }}
        onReset={() => {
          search(true);
        }}
        query={
          <>
            <TextField
              id="plan-filter-name"
              label={t('planName')}
              value={code}
              onValueChange={setCode}
            />
            <SelectField
              id="plan-filter-status"
              label={t('planStatus')}
              value={status}
              onValueChange={(value) => {
                setStatus(value as PlanStatus | '');
              }}
              options={[
                { value: '', label: t('planAllStates') },
                ...Object.entries(statuses).map(([value, key]) => ({ value, label: t(key) })),
              ]}
            />
          </>
        }
        cursorPagination={{
          hasPrevious: cursors.length > 1,
          hasNext: result?.ok === true && result.value.hasMore,
          onPrevious: () => {
            paginate(cursors.slice(0, -1));
          },
          onNext: () => {
            if (result?.ok && result.value.nextCursor)
              paginate([...cursors, result.value.nextCursor]);
          },
        }}
      />
      <PlanRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/plans/${id}`);
        }}
      />
    </PageLayout>
  );
}

function PlanDetails({ client, locale }: Props) {
  const { planId } = useParams();
  return <PlanDetailContent key={planId} planId={planId ?? ''} client={client} locale={locale} />;
}
function PlanDetailContent({ client, locale, planId }: Props & { readonly planId: string }) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [result, setResult] = useState<ConsoleApiResult<Plan>>();
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(true);
  const [guard, retryGuard] = useOperationGuard(client, planId, attempt);
  const [phase, setPhase] = useState<'ready' | 'pending' | 'unknown'>('ready');
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
    void client.getPlan(planId, controller.signal).then((next) => {
      if (!controller.signal.aborted) {
        setResult(next);
        setBusy(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, planId, attempt]);
  return (
    <PageLayout as="section" width="wide" title={<Heading title={t('planDetail')} />}>
      <ContentPanel title={t('planBasicInformation')}>
        {result === undefined ? (
          <p role="status">{t('planLoading')}</p>
        ) : result.ok ? (
          <DescriptionList
            items={[
              { label: t('planId'), value: result.value.id },
              { label: t('planName'), value: result.value.code },
              { label: t('planDisplayName'), value: result.value.displayName },
              { label: t('planLimit'), value: result.value.quotaLimits[0].limit },
              {
                label: t('planEligibility'),
                value: t(result.value.quotaLimits[0].limit < 1 ? 'planLegacyZero' : 'planPositive'),
              },
              {
                label: t('planStatus'),
                value: (
                  <StatusTag tone={statusTones[result.value.status]}>
                    {t(statuses[result.value.status])}
                  </StatusTag>
                ),
              },
              { label: t('planCreatedAt'), value: result.value.createdAt.toLocaleString(locale) },
            ]}
          />
        ) : (
          <PersistentError title={t('planReadFailed')}>
            <p>{t(result.problem.status === 404 ? 'planNotFound' : 'planReadHint')}</p>
            <p>{result.problem.code}</p>
          </PersistentError>
        )}
        {result?.ok &&
        result.value.status === 'DRAFT' &&
        result.value.quotaLimits[0].limit >= 1 &&
        !busy &&
        phase === 'ready' &&
        guard.status === 'clear' ? (
          <Button
            variant="primary"
            onClick={() => {
              if (pending.current) return;
              const controller = new AbortController();
              pending.current = controller;
              setPhase('pending');
              void client.activatePlan({ id: planId, signal: controller.signal }).then((next) => {
                if (controller.signal.aborted) return;
                if (next.ok) {
                  setBusy(true);
                  setAttempt((value) => value + 1);
                }
                setPhase(next.ok ? 'ready' : 'unknown');
                if (!next.ok) setProblem(next.problem.code);
                if (next.ok) pending.current = null;
              });
            }}
          >
            {t('planActivate')}
          </Button>
        ) : null}
        {result?.ok &&
        result.value.status === 'ACTIVE' &&
        result.value.quotaLimits[0].limit >= 1 ? (
          <p>{t('planActiveHint')}</p>
        ) : null}
        {phase === 'unknown' ? (
          <WarningFeedback title={t('planUnknown')}>
            <p>{t('planUnknownHint')}</p>
            <p>{problem}</p>
          </WarningFeedback>
        ) : null}
        {guard.status === 'pending' ? <p>{t('planPendingGuard')}</p> : null}
        {guard.status === 'failed' ? (
          <PersistentError title={t('planReadFailed')}>
            <p>{guard.problem}</p>
            <Button onClick={retryGuard}>{t('planRetry')}</Button>
          </PersistentError>
        ) : null}
        <PlanRecoveryPanel
          client={client}
          locale={locale}
          onView={(id) => {
            if (id === planId) {
              setPhase('ready');
              setProblem(undefined);
              pending.current = null;
              setBusy(true);
              setAttempt((value) => value + 1);
            } else void navigate(`/plans/${id}`);
          }}
        />
        <Button
          disabled={busy}
          onClick={() => {
            setBusy(true);
            setAttempt((value) => value + 1);
          }}
        >
          {t('planRetry')}
        </Button>
        <Button
          onClick={() => {
            void navigate('/plans');
          }}
        >
          {t('planBack')}
        </Button>
      </ContentPanel>
    </PageLayout>
  );
}

function useMaxUsers(client: ConsoleApiClient) {
  const [result, setResult] =
    useState<Awaited<ReturnType<ConsoleApiClient['listQuotaDefinitions']>>>();
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    void client
      .listQuotaDefinitions({ code: 'max_users', status: 'ACTIVE', signal: controller.signal })
      .then((next) => {
        if (!controller.signal.aborted) setResult(next);
      });
    return () => {
      controller.abort();
    };
  }, [client, attempt]);
  return [
    result,
    () => {
      setAttempt((value) => value + 1);
    },
  ] as const;
}

function PlanCreate({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [result, retryAvailability] = useMaxUsers(client);
  const [code, setCode] = useState('');
  const [guard, retryGuard] = useOperationGuard(client, undefined, 0, code);
  const [displayName, setDisplayName] = useState('');
  const [limit, setLimit] = useState('1');
  const [invalid, setInvalid] = useState(false);
  const [phase, setPhase] = useState<'ready' | 'pending' | 'unknown' | 'done'>('ready');
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  const dirty = phase === 'ready' && (code !== '' || displayName !== '' || limit !== '1');
  useFormExitGuard(dirty);
  const blocker = useBlocker(dirty);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  const definition = result?.ok
    ? result.value.items.find((item) => item.status === 'ACTIVE')
    : undefined;
  const valid =
    /^[a-z][a-z0-9-]{1,62}$/.test(code) &&
    displayName.trim().length > 0 &&
    displayName.length <= 200 &&
    /^\d+$/.test(limit) &&
    Number.isSafeInteger(Number(limit)) &&
    Number(limit) >= 1 &&
    Number(limit) <= 2147483647;
  return (
    <PageLayout as="section" width="wide" title={<Heading title={t('planCreate')} />}>
      <ContentPanel>
        <p>{t('planCreateDescription')}</p>
        {!result ? (
          <p role="status">{t('planLoading')}</p>
        ) : !result.ok ? (
          <PersistentError title={t('planReadFailed')}>
            <p>{result.problem.code}</p>
            <Button onClick={retryAvailability}>{t('planRetry')}</Button>
          </PersistentError>
        ) : !definition ? (
          <WarningFeedback title={t('planDefinitionRequired')}>
            <Button
              onClick={() => {
                void navigate('/quota-definitions');
              }}
            >
              {t('planViewDefinition')}
            </Button>
          </WarningFeedback>
        ) : (
          <p>{t('planDefinitionReady')}</p>
        )}
        {guard.status === 'pending' ? <p>{t('planPendingGuard')}</p> : null}
        {guard.status === 'failed' ? (
          <PersistentError title={t('planReadFailed')}>
            <p>{guard.problem}</p>
            <Button onClick={retryGuard}>{t('planRetry')}</Button>
          </PersistentError>
        ) : null}
        <FormLayout
          ariaLabel={t('planCreate')}
          onSubmit={(event) => {
            event.preventDefault();
            setInvalid(!valid);
            if (
              !valid ||
              !definition ||
              guard.status !== 'clear' ||
              pending.current ||
              phase !== 'ready'
            )
              return;
            const controller = new AbortController();
            pending.current = controller;
            setPhase('pending');
            void client
              .createPlan({
                request: {
                  code,
                  displayName,
                  quotaLimits: [{ quotaDefinitionId: definition.id, limit: Number(limit) }],
                },
                signal: controller.signal,
              })
              .then((next) => {
                if (controller.signal.aborted) return;
                if (next.ok) {
                  setPhase('done');
                  void navigate(`/plans/${next.value.id}`, { replace: true });
                } else {
                  setPhase('unknown');
                  setProblem(next.problem.code);
                }
              });
          }}
        >
          <TextField
            id="plan-code"
            label={t('planName')}
            value={code}
            onValueChange={setCode}
            disabled={phase !== 'ready'}
            required
          />
          <TextField
            id="plan-display-name"
            label={t('planDisplayName')}
            value={displayName}
            onValueChange={setDisplayName}
            disabled={phase !== 'ready'}
            required
          />
          <TextField
            id="plan-limit"
            label={t('planLimit')}
            value={limit}
            onValueChange={setLimit}
            disabled={phase !== 'ready'}
            required
            error={invalid && !valid ? t('planInvalid') : undefined}
          />
          <Button
            type="submit"
            variant="primary"
            disabled={!definition || guard.status !== 'clear' || phase !== 'ready'}
          >
            {t(phase === 'pending' ? 'planSaving' : 'planCreate')}
          </Button>
        </FormLayout>
        {phase === 'unknown' ? (
          <WarningFeedback title={t('planUnknown')}>
            <p>{t('planUnknownHint')}</p>
            <p>{problem}</p>
          </WarningFeedback>
        ) : null}
      </ContentPanel>
      <PlanRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/plans/${id}`);
        }}
      />
      <Button
        onClick={() => {
          void navigate('/plans');
        }}
      >
        {t('planBack')}
      </Button>
      <UnsavedChangesDialog
        open={blocker.state === 'blocked'}
        onContinueEditing={() => {
          if (blocker.state === 'blocked') blocker.reset();
        }}
        onDiscard={() => {
          if (blocker.state === 'blocked') blocker.proceed();
        }}
      />
    </PageLayout>
  );
}

type OperationGuard = {
  readonly code?: string;
  readonly status: 'loading' | 'clear' | 'pending' | 'failed';
  readonly problem?: string;
};

/** 先核对原操作者的全部相关记录，资源仍为空/DRAFT 不能授权新逻辑操作。 */
function useOperationGuard(client: ConsoleApiClient, targetId?: string, revision = 0, code = '') {
  const [guard, setGuard] = useState<OperationGuard>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    async function read() {
      let cursor: string | undefined;
      const visited = new Set<string>();
      for (;;) {
        const page = await client.listPlanOperations({
          cursor,
          limit: 100,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        if (!page.ok) {
          setGuard({ code, status: 'failed', problem: page.problem.code });
          return;
        }
        if (
          page.value.items.some(
            (operation) =>
              operation.state !== 'COMMITTED' &&
              (targetId === undefined
                ? operation.operation === 'CREATE' &&
                  (code === '' || operation.code === undefined || operation.code === code)
                : operation.operation === 'ACTIVATE' && operation.planId === targetId),
          )
        ) {
          setGuard({ code, status: 'pending' });
          return;
        }
        if (!page.value.hasMore) {
          setGuard({ code, status: 'clear' });
          return;
        }
        if (page.value.nextCursor === null || visited.has(page.value.nextCursor)) {
          setGuard({ code, status: 'failed', problem: 'INVALID_SERVICE_RESPONSE' });
          return;
        }
        cursor = page.value.nextCursor;
        visited.add(cursor);
      }
    }
    void read();
    return () => {
      controller.abort();
    };
  }, [client, targetId, revision, attempt, code]);
  return [
    guard.code === code ? guard : { status: 'loading' as const },
    () => {
      setGuard({ code, status: 'loading' });
      setAttempt((value) => value + 1);
    },
  ] as const;
}

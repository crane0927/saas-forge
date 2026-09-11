import type {
  ConsoleApiClient,
  ConsoleApiResult,
  ListQuotaDefinitionsInput,
  QuotaDefinition,
  QuotaDefinitionStatus,
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
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { QuotaDefinitionRecoveryPanel } from '@saas-forge/react-shell';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Route, Routes, useLocation, useNavigate, useParams } from 'react-router';
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
  DRAFT: 'quotaDraft',
  ACTIVE: 'quotaActive',
  RETIRED: 'quotaRetired',
} as const;

export function QuotaDefinitionRoutes(props: Props) {
  return (
    <Routes>
      <Route index element={<QuotaDefinitionList {...props} />} />
      <Route path="new" element={<QuotaDefinitionCreate {...props} />} />
      <Route path=":quotaDefinitionId" element={<QuotaDefinitionDetails {...props} />} />
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
        focusTargetId="quota-page-title"
      />
      <PageTitle headingId="quota-page-title" description={description} actions={actions}>
        {title}
      </PageTitle>
    </>
  );
}

function QuotaDefinitionList({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [availability, retryAvailability] = useMaxUsers(client);
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<QuotaDefinitionStatus | ''>('');
  const [query, setQuery] = useState<ListQuotaDefinitionsInput>({ limit: 50 });
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] =
    useState<Awaited<ReturnType<ConsoleApiClient['listQuotaDefinitions']>>>();
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    void client.listQuotaDefinitions({ ...query, signal: controller.signal }).then((next) => {
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
          title={t('quotaDefinitionsTitle')}
          description={t('quotaDefinitionsDescription')}
          actions={
            availability?.ok ? (
              <Button
                variant="primary"
                onClick={() => {
                  const existing = availability.value.items[0];
                  void navigate(
                    availability.value.items.length > 0
                      ? `/quota-definitions/${existing.id}`
                      : '/quota-definitions/new',
                  );
                }}
              >
                {t(availability.value.items.length > 0 ? 'quotaReuse' : 'quotaCreate')}
              </Button>
            ) : undefined
          }
        />
      }
    >
      {availability?.ok === false ? (
        <PersistentError title={t('quotaReadFailed')}>
          <p>{t('quotaReadHint')}</p>
          <p>{availability.problem.code}</p>
          <Button onClick={retryAvailability}>{t('quotaRetry')}</Button>
        </PersistentError>
      ) : null}
      <ServerTable<QuotaDefinition>
        presentation="panel"
        title={t('quotaListTitle')}
        selectable={false}
        ariaLabel={t('quotaDefinitionsTitle')}
        rows={result?.ok ? result.value.items : []}
        rowKey={(row) => row.id}
        columns={[
          { key: 'name', title: t('quotaName'), render: (row) => row.code },
          {
            key: 'status',
            title: t('quotaStatus'),
            render: (row) => (
              <StatusTag tone={statusTones[row.status]}>{t(statuses[row.status])}</StatusTag>
            ),
          },
        ]}
        actions={[
          {
            key: 'view',
            label: t('quotaView'),
            onAction: (row) => {
              void navigate(`/quota-definitions/${row.id}`);
            },
          },
        ]}
        page={cursors.length}
        pageSize={50}
        onTableChange={() => undefined}
        initialLoading={result === undefined}
        refreshing={busy && result !== undefined}
        loadError={
          result?.ok === false ? `${t('quotaReadFailed')}: ${result.problem.code}` : undefined
        }
        onRetry={() => {
          setBusy(true);
          setAttempt((value) => value + 1);
        }}
        filtered={Boolean(query.code || query.status)}
        emptyDescription={t('quotaEmpty')}
        onQuery={() => {
          search(false);
        }}
        onReset={() => {
          search(true);
        }}
        query={
          <>
            <TextField
              id="quota-filter-name"
              label={t('quotaName')}
              value={code}
              onValueChange={setCode}
            />
            <SelectField
              id="quota-filter-status"
              label={t('quotaStatus')}
              value={status}
              onValueChange={(value) => {
                setStatus(value as QuotaDefinitionStatus | '');
              }}
              options={[
                { value: '', label: t('quotaAllStates') },
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
      <QuotaDefinitionRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/quota-definitions/${id}`);
        }}
      />
    </PageLayout>
  );
}

function QuotaDefinitionDetails({ client, locale }: Props) {
  const { quotaDefinitionId } = useParams();
  return (
    <QuotaDefinitionDetailContent
      key={quotaDefinitionId}
      quotaDefinitionId={quotaDefinitionId ?? ''}
      client={client}
      locale={locale}
    />
  );
}
function QuotaDefinitionDetailContent({
  client,
  locale,
  quotaDefinitionId,
}: Props & { readonly quotaDefinitionId: string }) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [result, setResult] = useState<ConsoleApiResult<QuotaDefinition>>();
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(true);
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
    void client.getQuotaDefinition(quotaDefinitionId, controller.signal).then((next) => {
      if (!controller.signal.aborted) {
        setResult(next);
        setBusy(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, quotaDefinitionId, attempt]);
  return (
    <PageLayout as="section" width="wide" title={<Heading title={t('quotaDetail')} />}>
      <ContentPanel title={t('quotaBasicInformation')}>
        {result === undefined ? (
          <p role="status">{t('quotaLoading')}</p>
        ) : result.ok ? (
          <DescriptionList
            items={[
              { label: t('quotaId'), value: result.value.id },
              { label: t('quotaName'), value: result.value.code },
              {
                label: t('quotaStatus'),
                value: (
                  <StatusTag tone={statusTones[result.value.status]}>
                    {t(statuses[result.value.status])}
                  </StatusTag>
                ),
              },
              { label: t('quotaCreatedAt'), value: result.value.createdAt.toLocaleString(locale) },
            ]}
          />
        ) : (
          <PersistentError title={t('quotaReadFailed')}>
            <p>{t(result.problem.status === 404 ? 'quotaNotFound' : 'quotaReadHint')}</p>
            <p>{result.problem.code}</p>
          </PersistentError>
        )}
        {result?.ok && result.value.status === 'DRAFT' && !busy && phase === 'ready' ? (
          <Button
            variant="primary"
            onClick={() => {
              if (pending.current) return;
              const controller = new AbortController();
              pending.current = controller;
              setPhase('pending');
              void client
                .activateQuotaDefinition({ id: quotaDefinitionId, signal: controller.signal })
                .then((next) => {
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
            {t('quotaActivate')}
          </Button>
        ) : null}
        {result?.ok && result.value.status === 'ACTIVE' ? <p>{t('quotaActiveHint')}</p> : null}
        {phase === 'unknown' ? (
          <WarningFeedback title={t('quotaUnknown')}>
            <p>{t('quotaUnknownHint')}</p>
            <p>{problem}</p>
          </WarningFeedback>
        ) : null}
        <QuotaDefinitionRecoveryPanel
          client={client}
          locale={locale}
          onView={(id) => {
            if (id === quotaDefinitionId) {
              setBusy(true);
              setAttempt((value) => value + 1);
            } else void navigate(`/quota-definitions/${id}`);
          }}
        />
        <Button
          disabled={busy}
          onClick={() => {
            setBusy(true);
            setAttempt((value) => value + 1);
          }}
        >
          {t('quotaRetry')}
        </Button>
        <Button
          onClick={() => {
            void navigate('/quota-definitions');
          }}
        >
          {t('quotaBack')}
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
      .listQuotaDefinitions({ code: 'max_users', signal: controller.signal })
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

function QuotaDefinitionCreate({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [result, retryAvailability] = useMaxUsers(client);
  const [phase, setPhase] = useState<'ready' | 'pending' | 'unknown'>('ready');
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  const existing = result?.ok ? result.value.items[0] : undefined;
  return (
    <PageLayout as="section" width="wide" title={<Heading title={t('quotaCreate')} />}>
      <ContentPanel>
        <p>{t('quotaCreateDescription')}</p>
        {!result ? (
          <p role="status">{t('quotaLoading')}</p>
        ) : !result.ok ? (
          <PersistentError title={t('quotaReadFailed')}>
            <p>{result.problem.code}</p>
            <p>{t('quotaReadHint')}</p>
            <Button onClick={retryAvailability}>{t('quotaRetry')}</Button>
          </PersistentError>
        ) : existing ? (
          <Button
            onClick={() => {
              void navigate(`/quota-definitions/${existing.id}`, { replace: true });
            }}
          >
            {t('quotaReuse')}
          </Button>
        ) : (
          <Button
            variant="primary"
            disabled={phase !== 'ready'}
            onClick={() => {
              if (pending.current) return;
              const controller = new AbortController();
              pending.current = controller;
              setPhase('pending');
              void client.createQuotaDefinition({ signal: controller.signal }).then((next) => {
                if (controller.signal.aborted) return;
                if (next.ok)
                  void navigate(`/quota-definitions/${next.value.id}`, { replace: true });
                else {
                  setPhase('unknown');
                  setProblem(next.problem.code);
                }
              });
            }}
          >
            {t(phase === 'pending' ? 'quotaSaving' : 'quotaCreate')}
          </Button>
        )}
        {phase === 'unknown' ? (
          <WarningFeedback title={t('quotaUnknown')}>
            <p>{t('quotaUnknownHint')}</p>
            <p>{problem}</p>
          </WarningFeedback>
        ) : null}
      </ContentPanel>
      <QuotaDefinitionRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/quota-definitions/${id}`);
        }}
      />
      <Button
        onClick={() => {
          void navigate('/quota-definitions');
        }}
      >
        {t('quotaBack')}
      </Button>
    </PageLayout>
  );
}

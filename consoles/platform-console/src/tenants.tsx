import type {
  ConsoleApiClient,
  ConsoleApiResult,
  ListTenantsInput,
  Tenant,
  TenantStatus,
} from '@saas-forge/app-runtime';
import {
  Button,
  FormLayout,
  PersistentError,
  RouteFocusAnnouncement,
  SelectField,
  ServerTable,
  TextField,
  UnsavedChangesDialog,
  WarningFeedback,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { TenantCreationRecoveryPanel, useFormExitGuard } from '@saas-forge/react-shell';
import { useEffect, useRef, useState } from 'react';
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
const statuses = {
  PENDING: 'tenantPending',
  ACTIVE: 'tenantActive',
  SUSPENDED: 'tenantSuspended',
  CLOSED: 'tenantClosed',
} as const;

export function TenantRoutes(props: Props) {
  return (
    <Routes>
      <Route index element={<TenantList {...props} />} />
      <Route path="new" element={<TenantCreate {...props} />} />
      <Route path=":tenantId" element={<TenantDetails {...props} />} />
    </Routes>
  );
}

function Heading({ title }: { readonly title: string }) {
  const location = useLocation();
  return (
    <>
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="tenant-page-title"
      />
      <h1 id="tenant-page-title" tabIndex={-1}>
        {title}
      </h1>
    </>
  );
}

function TenantList({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [status, setStatus] = useState<TenantStatus | ''>('');
  const [query, setQuery] = useState<ListTenantsInput>({ limit: 50 });
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Awaited<ReturnType<ConsoleApiClient['listTenants']>>>();
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    void client.listTenants({ ...query, signal: controller.signal }).then((next) => {
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
      setName('');
      setStatus('');
    }
    setCursors([undefined]);
    setBusy(true);
    setQuery({
      limit: 50,
      name: reset ? undefined : name,
      status: reset || status === '' ? undefined : status,
    });
  }
  function paginate(next: (string | undefined)[]) {
    setCursors(next);
    setBusy(true);
    setQuery({ ...query, cursor: next.at(-1) });
  }
  return (
    <section>
      <Heading title={t('tenantsTitle')} />
      <p>{t('tenantsDescription')}</p>
      <Button
        variant="primary"
        onClick={() => {
          void navigate('/tenants/new');
        }}
      >
        {t('tenantCreate')}
      </Button>
      <ServerTable<Tenant>
        ariaLabel={t('tenantsTitle')}
        rows={result?.ok ? result.value.items : []}
        rowKey={(row) => row.id}
        columns={[
          { key: 'name', title: t('tenantName'), render: (row) => row.displayName },
          { key: 'status', title: t('tenantStatus'), render: (row) => t(statuses[row.status]) },
        ]}
        actions={[
          {
            key: 'view',
            label: t('tenantView'),
            onAction: (row) => {
              void navigate(`/tenants/${row.id}`);
            },
          },
        ]}
        page={cursors.length}
        pageSize={50}
        onTableChange={() => undefined}
        initialLoading={result === undefined}
        refreshing={busy && result !== undefined}
        loadError={result?.ok === false ? t('tenantReadFailed') : undefined}
        onRetry={() => {
          setBusy(true);
          setAttempt((value) => value + 1);
        }}
        filtered={Boolean(query.name || query.status)}
        emptyDescription={t('tenantEmpty')}
        onQuery={() => {
          search(false);
        }}
        onReset={() => {
          search(true);
        }}
        query={
          <>
            <TextField
              id="tenant-filter-name"
              label={t('tenantName')}
              value={name}
              onValueChange={setName}
            />
            <SelectField
              id="tenant-filter-status"
              label={t('tenantStatus')}
              value={status}
              onValueChange={(value) => {
                setStatus(value as TenantStatus | '');
              }}
              options={[
                { value: '', label: t('tenantAllStates') },
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
      <TenantCreationRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/tenants/${id}`);
        }}
      />
    </section>
  );
}

function TenantCreate({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [phase, setPhase] = useState<'editing' | 'submitting' | 'unknown'>('editing');
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  const dirty = phase === 'editing' && name.length > 0;
  useFormExitGuard(dirty);
  const blocker = useBlocker(dirty);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  return (
    <section>
      <Heading title={t('tenantCreate')} />
      <p>{t('tenantCreateDescription')}</p>
      <FormLayout
        ariaLabel={t('tenantCreate')}
        onSubmit={(event) => {
          event.preventDefault();
          if (pending.current !== null || phase !== 'editing') return;
          if (name.trim().length === 0 || name.length > 200) {
            setInvalid(true);
            return;
          }
          const controller = new AbortController();
          pending.current = controller;
          setPhase('submitting');
          void client
            .createTenant({ request: { displayName: name }, signal: controller.signal })
            .then((result) => {
              if (controller.signal.aborted) return;
              if (result.ok) {
                void navigate(`/tenants/${result.value.id}`, { replace: true });
              } else {
                setProblem(result.problem.code);
                // 发出请求后的不确定结果不得被表单当成可重新创建的普通失败。
                setPhase('unknown');
              }
            });
        }}
      >
        <TextField
          id="tenant-create-name"
          label={t('tenantName')}
          value={name}
          onValueChange={setName}
          disabled={phase !== 'editing'}
          required
          error={invalid ? t('tenantNameError') : undefined}
        />
        <p>{t('tenantExpiryHint')}</p>
        <Button type="submit" variant="primary" disabled={phase !== 'editing'}>
          {t(phase === 'submitting' ? 'tenantSaving' : 'tenantCreate')}
        </Button>
      </FormLayout>
      {phase === 'unknown' ? (
        <WarningFeedback title={t('tenantUnknown')}>
          <p>{t('tenantUnknownHint')}</p>
          <p>{problem}</p>
        </WarningFeedback>
      ) : null}
      <TenantCreationRecoveryPanel
        client={client}
        locale={locale}
        onView={(id) => {
          void navigate(`/tenants/${id}`);
        }}
      />
      <Button
        onClick={() => {
          void navigate('/tenants');
        }}
      >
        {t('tenantBack')}
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
    </section>
  );
}

function TenantDetails({ client, locale }: Props) {
  const { tenantId } = useParams();
  return (
    <TenantDetailContent key={tenantId} tenantId={tenantId ?? ''} client={client} locale={locale} />
  );
}
function TenantDetailContent({ client, locale, tenantId }: Props & { readonly tenantId: string }) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [result, setResult] = useState<ConsoleApiResult<Tenant>>();
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    void client.getTenant(tenantId, controller.signal).then((next) => {
      if (!controller.signal.aborted) {
        setResult(next);
        setBusy(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, tenantId, attempt]);
  return (
    <section>
      <Heading title={t('tenantDetail')} />
      {result === undefined ? (
        <p role="status">{t('currentSessionLoading')}</p>
      ) : result.ok ? (
        <dl>
          <dt>{t('tenantId')}</dt>
          <dd>{result.value.id}</dd>
          <dt>{t('tenantName')}</dt>
          <dd>{result.value.displayName}</dd>
          <dt>{t('tenantStatus')}</dt>
          <dd>{t(statuses[result.value.status])}</dd>
          <dt>{t('tenantCreatedAt')}</dt>
          <dd>{result.value.createdAt.toLocaleString(locale)}</dd>
          <dt>{t('tenantExpiresAt')}</dt>
          <dd>
            {result.value.expiresAt === null
              ? t('tenantNoExpiry')
              : result.value.expiresAt.toLocaleString(locale)}
          </dd>
        </dl>
      ) : (
        <PersistentError title={t('tenantReadFailed')}>
          <p>{t(result.problem.status === 404 ? 'tenantNotFound' : 'tenantReadHint')}</p>
          <p>{result.problem.code}</p>
        </PersistentError>
      )}
      <Button
        disabled={busy}
        onClick={() => {
          setBusy(true);
          setAttempt((value) => value + 1);
        }}
      >
        {t('tenantRetry')}
      </Button>
      <Button
        onClick={() => {
          void navigate('/tenants');
        }}
      >
        {t('tenantBack')}
      </Button>
    </section>
  );
}

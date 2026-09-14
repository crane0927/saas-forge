import type {
  ConsoleApiClient,
  ListOAuthClientsInput,
  OAuthClientDetail,
  OAuthClientStatus,
  OAuthClientType,
} from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  DescriptionList,
  PageLayout,
  PageTitle,
  PersistentError,
  RouteFocusAnnouncement,
  SelectField,
  ServerTable,
  StatusTag,
  TextField,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useState } from 'react';
import { Route, Routes, useLocation, useNavigate, useParams } from 'react-router';
import { platformMessages } from './messages';
import {
  OAuthClientCreate,
  OAuthClientOperations,
  OAuthClientCredentialActions,
} from './oauth-client-management';

type Props = { readonly client: ConsoleApiClient; readonly locale: SupportedLocale };
const statuses = { ACTIVE: 'oauthActive', REVOKED: 'oauthRevoked' } as const;
const types = { RUNTIME_SERVICE: 'oauthRuntime', RESERVED_SERVICE: 'oauthReserved' } as const;
const statusTones = { ACTIVE: 'success', REVOKED: 'danger' } as const;
function translator(locale: SupportedLocale) {
  return createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
}
export function OAuthClientRoutes(props: Props) {
  return (
    <Routes>
      <Route index element={<OAuthClientList {...props} />} />
      <Route path="new" element={<OAuthClientCreate {...props} />} />
      <Route path="operations" element={<OAuthClientOperations {...props} />} />
      <Route path=":clientId" element={<OAuthClientDetails {...props} />} />
    </Routes>
  );
}
function Heading({
  title,
  description,
}: {
  readonly title: string;
  readonly description?: string;
}) {
  const location = useLocation();
  return (
    <>
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="oauth-page-title"
      />
      <PageTitle headingId="oauth-page-title" description={description}>
        {title}
      </PageTitle>
    </>
  );
}
function OAuthClientList({ client, locale }: Props) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [status, setStatus] = useState<OAuthClientStatus | ''>('');
  const [clientType, setClientType] = useState<OAuthClientType | ''>('');
  const [query, setQuery] = useState<ListOAuthClientsInput>({ limit: 50 });
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Awaited<ReturnType<ConsoleApiClient['listOAuthClients']>>>();
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    void client.listOAuthClients({ ...query, signal: controller.signal }).then((next) => {
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
      setClientType('');
    }
    setCursors([undefined]);
    setBusy(true);
    setQuery({
      limit: 50,
      name: reset ? undefined : name,
      clientType: reset || clientType === '' ? undefined : clientType,
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
      title={<Heading title={t('oauthClientsTitle')} description={t('oauthClientsDescription')} />}
    >
      <Button onClick={() => void navigate('/oauth-clients/new')}>{t('oauthCreate')}</Button>
      <Button onClick={() => void navigate('/oauth-clients/operations')}>
        {t('oauthOperations')}
      </Button>
      <ServerTable<OAuthClientDetail>
        presentation="panel"
        title={t('oauthClientsTitle')}
        selectable={false}
        ariaLabel={t('oauthClientsTitle')}
        rows={result?.ok ? result.value.items : []}
        rowKey={(row) => row.clientId}
        columns={[
          { key: 'name', title: t('oauthName'), render: (row) => row.displayName },
          { key: 'type', title: t('oauthType'), render: (row) => t(types[row.clientType]) },
          {
            key: 'service',
            title: t('oauthService'),
            render: (row) => row.reservedServiceKey ?? t('oauthNotReserved'),
          },
          {
            key: 'status',
            title: t('oauthStatus'),
            render: (row) => (
              <StatusTag tone={statusTones[row.status]}>{t(statuses[row.status])}</StatusTag>
            ),
          },
        ]}
        actions={[
          {
            key: 'view',
            label: t('oauthView'),
            onAction: (row) => {
              void navigate(`/oauth-clients/${row.clientId}`);
            },
          },
        ]}
        page={cursors.length}
        pageSize={50}
        onTableChange={() => undefined}
        initialLoading={busy && result === undefined}
        refreshing={busy && result !== undefined}
        loadError={
          result?.ok === false
            ? t(result.problem.status === 403 ? 'oauthDenied' : 'oauthReadFailed')
            : undefined
        }
        onRetry={() => {
          setBusy(true);
          setAttempt((value) => value + 1);
        }}
        filtered={Boolean(query.name || query.status || query.clientType)}
        emptyDescription={t('oauthEmpty')}
        onQuery={() => {
          search(false);
        }}
        onReset={() => {
          search(true);
        }}
        query={
          <>
            <TextField
              id="oauth-filter-name"
              label={t('oauthName')}
              value={name}
              onValueChange={setName}
            />
            <SelectField
              id="oauth-filter-type"
              label={t('oauthType')}
              value={clientType}
              onValueChange={(value) => {
                setClientType(value as OAuthClientType | '');
              }}
              options={[
                { value: '', label: t('oauthAll') },
                ...Object.entries(types).map(([value, key]) => ({ value, label: t(key) })),
              ]}
            />
            <SelectField
              id="oauth-filter-status"
              label={t('oauthStatus')}
              value={status}
              onValueChange={(value) => {
                setStatus(value as OAuthClientStatus | '');
              }}
              options={[
                { value: '', label: t('oauthAll') },
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
    </PageLayout>
  );
}

function OAuthClientDetails(props: Props) {
  const { clientId = '' } = useParams();
  return <OAuthClientDetailContent key={clientId} {...props} clientId={clientId} />;
}
function OAuthClientDetailContent({
  client,
  locale,
  clientId,
}: Props & { readonly clientId: string }) {
  const translate = translator(locale);
  const t = translate.translate.bind(translate);
  const navigate = useNavigate();
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(true);
  const [result, setResult] = useState<Awaited<ReturnType<ConsoleApiClient['getOAuthClient']>>>();
  useEffect(() => {
    const controller = new AbortController();
    void client.getOAuthClient({ clientId, signal: controller.signal }).then((next) => {
      if (!controller.signal.aborted) {
        setResult(next);
        setBusy(false);
      }
    });
    return () => {
      controller.abort();
    };
  }, [client, clientId, attempt]);
  return (
    <PageLayout as="section" width="wide" title={<Heading title={t('oauthDetail')} />}>
      <ContentPanel title={t('oauthInformation')}>
        {result === undefined ? (
          <p role="status">{t('oauthLoading')}</p>
        ) : result.ok ? (
          <DescriptionList
            items={[
              { label: t('oauthId'), value: result.value.clientId },
              { label: t('oauthName'), value: result.value.displayName },
              { label: t('oauthType'), value: t(types[result.value.clientType]) },
              {
                label: t('oauthService'),
                value: result.value.reservedServiceKey ?? t('oauthNotReserved'),
              },
              {
                label: t('oauthStatus'),
                value: (
                  <StatusTag tone={statusTones[result.value.status]}>
                    {t(statuses[result.value.status])}
                  </StatusTag>
                ),
              },
              { label: t('oauthScopes'), value: [...result.value.allowedScopes].join(', ') },
              { label: t('oauthCreatedAt'), value: result.value.createdAt.toLocaleString(locale) },
              { label: t('oauthUpdatedAt'), value: result.value.updatedAt.toLocaleString(locale) },
              {
                label: t('oauthRevokedAt'),
                value: result.value.revokedAt?.toLocaleString(locale) ?? t('oauthNotRevoked'),
              },
            ]}
          />
        ) : (
          <PersistentError title={t('oauthReadFailed')}>
            <p>
              {t(
                result.problem.status === 403
                  ? 'oauthDenied'
                  : result.problem.status === 404
                    ? 'oauthNotFound'
                    : 'oauthReadHint',
              )}
            </p>
          </PersistentError>
        )}
        <Button
          disabled={busy}
          onClick={() => {
            setBusy(true);
            setAttempt((value) => value + 1);
          }}
        >
          {t('oauthReload')}
        </Button>
        <Button
          onClick={() => {
            void navigate('/oauth-clients');
          }}
        >
          {t('oauthBack')}
        </Button>
      </ContentPanel>
      {result?.ok && result.value.clientType === 'RUNTIME_SERVICE' ? (
        <OAuthClientCredentialActions
          client={client}
          locale={locale}
          clientId={clientId}
          onChanged={() => {
            setAttempt((value) => value + 1);
          }}
        />
      ) : null}
    </PageLayout>
  );
}

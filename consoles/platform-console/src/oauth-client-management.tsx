import type { AuthenticationProblem, ConsoleApiClient } from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  FormLayout,
  FormRow,
  IrreversibleDangerDialog,
  PageLayout,
  PageTitle,
  PersistentError,
  RecoverableDangerDialog,
  RouteFocusAnnouncement,
  SelectField,
  TextField,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { platformMessages } from './messages';

type Props = { readonly client: ConsoleApiClient; readonly locale: SupportedLocale };
function ManagementHeading({ title }: { readonly title: string }) {
  const location = useLocation();
  return (
    <>
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="oauth-management-title"
      />
      <PageTitle headingId="oauth-management-title">{title}</PageTitle>
    </>
  );
}
function translator(locale: SupportedLocale) {
  return createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
}

function SecretDisplay({
  secret,
  locale,
  onClose,
}: {
  readonly secret: string;
  readonly locale: SupportedLocale;
  readonly onClose: () => void;
}) {
  const t = translator(locale).translate.bind(translator(locale));
  const [copied, setCopied] = useState<'ready' | 'done' | 'failed'>('ready');
  useEffect(() => {
    const clear = () => {
      onClose();
    };
    window.addEventListener('pagehide', clear);
    return () => {
      window.removeEventListener('pagehide', clear);
    };
  }, [onClose]);
  return (
    <ContentPanel title={t('oauthSecretTitle')}>
      <p>{t('oauthSecretWarning')}</p>
      <output aria-label={t('oauthSecretTitle')}>{secret}</output>
      <Button
        onClick={() => {
          void Promise.resolve()
            .then(() => navigator.clipboard.writeText(secret))
            .then(
              () => {
                setCopied('done');
              },
              () => {
                setCopied('failed');
              },
            );
        }}
      >
        {t('oauthCopy')}
      </Button>
      <Button onClick={onClose}>{t('oauthCloseSecret')}</Button>
      {copied !== 'ready' ? (
        <p role="status">{t(copied === 'done' ? 'oauthCopied' : 'oauthCopyFailed')}</p>
      ) : null}
    </ContentPanel>
  );
}

export function OAuthClientCreate({ client, locale }: Props) {
  const t = translator(locale).translate.bind(translator(locale));
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [scope, setScope] = useState('read');
  const [secret, setSecret] = useState<string>();
  const [clientId, setClientId] = useState<string>();
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const request = useRef<AbortController | undefined>(undefined);
  useEffect(() => () => request.current?.abort(), []);
  return (
    <PageLayout as="section" title={<ManagementHeading title={t('oauthCreate')} />}>
      {!submitted ? (
        <FormLayout
          ariaLabel={t('oauthCreate')}
          onSubmit={(event) => {
            event.preventDefault();
            if (request.current !== undefined) return;
            const controller = new AbortController();
            request.current = controller;
            setBusy(true);
            setSubmitted(true);
            const allowedScopes = new Set<'runtime:read' | 'runtime:quota:write'>(
              scope === 'both'
                ? ['runtime:read', 'runtime:quota:write']
                : scope === 'write'
                  ? ['runtime:quota:write']
                  : ['runtime:read'],
            );
            void client
              .createOAuthClient({
                request: { displayName: name, allowedScopes },
                signal: controller.signal,
              })
              .then((result) => {
                if (controller.signal.aborted) return;
                request.current = undefined;
                setBusy(false);
                if (result.ok) {
                  setClientId(result.value.clientId);
                  setSecret(result.value.clientSecret);
                } else {
                  setProblem(result.problem);
                  if (result.problem.status === 400 || result.problem.status === 403)
                    setSubmitted(false);
                }
              });
          }}
        >
          <FormRow>
            <TextField
              id="oauth-create-name"
              label={t('oauthName')}
              value={name}
              onValueChange={setName}
              required
            />
          </FormRow>
          <FormRow>
            <SelectField
              id="oauth-create-scopes"
              label={t('oauthScopes')}
              value={scope}
              onValueChange={setScope}
              options={[
                { value: 'read', label: 'runtime:read' },
                { value: 'write', label: 'runtime:quota:write' },
                { value: 'both', label: 'runtime:read + runtime:quota:write' },
              ]}
            />
          </FormRow>
          <Button type="submit" variant="primary">
            {t('oauthCreate')}
          </Button>
        </FormLayout>
      ) : null}
      {busy ? <p role="status">{t('oauthLoading')}</p> : null}
      {problem ? (
        <PersistentError title={t('oauthUnknown')}>
          <p>{t('lifecycleIssue', { code: problem.code, traceId: problem.traceId ?? '—' })}</p>
        </PersistentError>
      ) : null}
      {secret !== undefined ? (
        <SecretDisplay
          secret={secret}
          locale={locale}
          onClose={() => {
            setSecret(undefined);
          }}
        />
      ) : null}
      {clientId ? (
        <Button
          onClick={() => {
            setSecret(undefined);
            void navigate(`/oauth-clients/${clientId}`);
          }}
        >
          {t('oauthView')}
        </Button>
      ) : null}
      <Button
        onClick={() => {
          setSecret(undefined);
          void navigate('/oauth-clients/operations');
        }}
      >
        {t('oauthOperations')}
      </Button>
    </PageLayout>
  );
}

export function OAuthClientOperations({ client, locale }: Props) {
  const t = translator(locale).translate.bind(translator(locale));
  const navigate = useNavigate();
  const [read, setRead] =
    useState<Awaited<ReturnType<ConsoleApiClient['listOAuthClientOperations']>>>();
  const [cursor, setCursor] = useState<string>();
  const [revision, setRevision] = useState(0);
  const [secret, setSecret] = useState<string>();
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const [busy, setBusy] = useState(false);
  type Operation = Extract<
    Awaited<ReturnType<ConsoleApiClient['listOAuthClientOperations']>>,
    { ok: true }
  >['value']['items'][number];
  const [selection, setSelection] = useState<Operation>();
  const pending = useRef<AbortController | undefined>(undefined);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setRead(undefined);
    void client.listOAuthClientOperations(cursor, controller.signal).then((result) => {
      if (!controller.signal.aborted) setRead(result);
    });
    return () => {
      controller.abort();
    };
  }, [client, cursor, revision]);
  return (
    <PageLayout as="section" title={<ManagementHeading title={t('oauthOperations')} />}>
      <p>{t('oauthOperationsHint')}</p>
      <p role="status">{t('oauthMissingOperationUnknown')}</p>
      {secret !== undefined ? (
        <SecretDisplay
          secret={secret}
          locale={locale}
          onClose={() => {
            setSecret(undefined);
          }}
        />
      ) : null}
      {problem ? (
        <PersistentError title={t('oauthUnknown')}>
          <p>{t('lifecycleIssue', { code: problem.code, traceId: problem.traceId ?? '—' })}</p>
        </PersistentError>
      ) : null}
      {read === undefined ? (
        <p role="status">{t('oauthLoading')}</p>
      ) : !read.ok ? (
        <PersistentError title={t('oauthReadFailed')} />
      ) : (
        read.value.items.map((operation) => (
          <ContentPanel key={operation.operationId} title={operation.displayName}>
            <p>{operation.clientId}</p>
            <p>
              {t(
                {
                  CREATE: 'oauthCreated',
                  ROTATE: 'oauthRotated',
                  RECOVER: 'oauthRecovered',
                  REVOKE: 'oauthRevoked',
                }[operation.action] as
                  'oauthCreated' | 'oauthRotated' | 'oauthRecovered' | 'oauthRevoked',
              )}{' '}
              · {operation.completedAt.toLocaleString(locale)}
            </p>
            {operation.recoveryUntil ? (
              <p>
                {t('oauthRecoveryUntil', { time: operation.recoveryUntil.toLocaleString(locale) })}
              </p>
            ) : null}
            {operation.canRecover ? (
              <Button
                disabled={busy || secret !== undefined}
                onClick={() => {
                  setSelection(operation);
                }}
              >
                {t('oauthRecoverSecret')}
              </Button>
            ) : operation.action !== 'REVOKE' ? (
              <>
                <p>{t('oauthReplacementHint')}</p>
                <Button
                  disabled={busy}
                  onClick={() => {
                    setSecret(undefined);
                    void navigate('/oauth-clients/new');
                  }}
                >
                  {t('oauthCreateReplacement')}
                </Button>
              </>
            ) : null}
            <Button
              disabled={busy}
              onClick={() => {
                setSecret(undefined);
                void navigate(`/oauth-clients/${operation.clientId}`);
              }}
            >
              {t('oauthView')}
            </Button>
          </ContentPanel>
        ))
      )}
      <Button
        disabled={busy}
        onClick={() => {
          setCursor(undefined);
          setRevision((v) => v + 1);
        }}
      >
        {t('oauthReload')}
      </Button>
      {read?.ok && read.value.hasMore ? (
        <Button
          disabled={busy}
          onClick={() => {
            setCursor(read.value.nextCursor ?? undefined);
          }}
        >
          {t('oauthOlderOperations')}
        </Button>
      ) : null}
      <RecoverableDangerDialog
        open={selection !== undefined}
        title={t('oauthRecoverSecret')}
        objectName={selection?.displayName ?? ''}
        consequence={t('oauthRecoveryConsequence')}
        actionLabel={t('oauthRecoverSecret')}
        onCancel={() => {
          setSelection(undefined);
        }}
        onConfirm={() => {
          if (selection === undefined || pending.current !== undefined) return;
          const operation = selection;
          setSelection(undefined);
          setBusy(true);
          const controller = new AbortController();
          pending.current = controller;
          void client.recoverOAuthClientOperation(operation, controller.signal).then((result) => {
            if (controller.signal.aborted) return;
            pending.current = undefined;
            setBusy(false);
            setRevision((v) => v + 1);
            if (result.ok) {
              setSecret(result.value.clientSecret);
              setProblem(undefined);
            } else setProblem(result.problem);
          });
        }}
      />
    </PageLayout>
  );
}

export function OAuthClientCredentialActions({
  client,
  locale,
  clientId,
  onChanged,
}: Props & { readonly clientId: string; readonly onChanged: () => void }) {
  const t = translator(locale).translate.bind(translator(locale));
  const navigate = useNavigate();
  const [read, setRead] =
    useState<Awaited<ReturnType<ConsoleApiClient['getOAuthClientCredentialStatus']>>>();
  const [revision, setRevision] = useState(0);
  const [secret, setSecret] = useState<string>();
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const [confirmation, setConfirmation] = useState<'rotate' | 'revoke'>();
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<AbortController | undefined>(undefined);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setRead(undefined);
    void client.getOAuthClientCredentialStatus(clientId, controller.signal).then((result) => {
      if (!controller.signal.aborted) setRead(result);
    });
    return () => {
      controller.abort();
    };
  }, [client, clientId, revision]);
  async function submit() {
    if (confirmation === undefined || pending.current !== undefined) return;
    const action = confirmation;
    setConfirmation(undefined);
    setBusy(true);
    const controller = new AbortController();
    pending.current = controller;
    const result =
      action === 'rotate'
        ? await client.rotateOAuthClientSecret(clientId, controller.signal)
        : await client.revokeOAuthClient(clientId, controller.signal);
    if (controller.signal.aborted) return;
    pending.current = undefined;
    setBusy(false);
    setRevision((v) => v + 1);
    onChanged();
    if (result.ok) {
      setProblem(undefined);
      if (result.value !== undefined) setSecret(result.value.clientSecret);
    } else {
      setProblem(result.problem);
      setUncertain(true);
    }
  }
  return (
    <ContentPanel title={t('oauthCredentials')}>
      {secret !== undefined ? (
        <SecretDisplay
          secret={secret}
          locale={locale}
          onClose={() => {
            setSecret(undefined);
          }}
        />
      ) : null}
      {read === undefined ? (
        <p role="status">{t('oauthLoading')}</p>
      ) : !read.ok ? (
        <PersistentError title={t('oauthReadFailed')} />
      ) : (
        <>
          {read.value.overlapEndsAt ? (
            <p>
              {t('oauthOverlapUntil', { time: read.value.overlapEndsAt.toLocaleString(locale) })}
            </p>
          ) : null}
          {read.value.canRotate ? (
            <Button
              disabled={busy || uncertain || secret !== undefined}
              onClick={() => {
                setConfirmation('rotate');
              }}
            >
              {t('oauthRotate')}
            </Button>
          ) : null}
          {read.value.canRevoke ? (
            <Button
              disabled={busy || uncertain || secret !== undefined}
              onClick={() => {
                setConfirmation('revoke');
              }}
            >
              {t('oauthRevoke')}
            </Button>
          ) : null}
        </>
      )}
      {problem ? (
        <PersistentError title={t('oauthUnknown')}>
          <p>{t('lifecycleIssue', { code: problem.code, traceId: problem.traceId ?? '—' })}</p>
        </PersistentError>
      ) : null}
      <Button
        disabled={busy}
        onClick={() => {
          setSecret(undefined);
          void navigate('/oauth-clients/operations');
        }}
      >
        {t('oauthOperations')}
      </Button>
      <RecoverableDangerDialog
        open={confirmation === 'rotate'}
        title={t('oauthRotate')}
        objectName={clientId}
        consequence={t('oauthRotateConsequence')}
        actionLabel={t('oauthRotate')}
        onCancel={() => {
          setConfirmation(undefined);
        }}
        onConfirm={() => void submit()}
      />
      <IrreversibleDangerDialog
        open={confirmation === 'revoke'}
        title={t('oauthRevoke')}
        objectName={clientId}
        consequence={t('oauthRevokeConsequence')}
        actionLabel={t('oauthRevoke')}
        onCancel={() => {
          setConfirmation(undefined);
        }}
        onConfirm={() => void submit()}
      />
    </ContentPanel>
  );
}

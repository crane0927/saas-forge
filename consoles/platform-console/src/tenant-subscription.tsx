import type {
  ConsoleApiClient,
  ConsoleApiResult,
  Plan,
  Tenant,
  TenantSubscription,
} from '@saas-forge/app-runtime';
import {
  Button,
  ContentPanel,
  DescriptionList,
  FormLayout,
  PersistentError,
  SelectField,
  StatusTag,
  TextField,
  UnsavedChangesDialog,
  WarningFeedback,
} from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { SubscriptionRecoveryPanel, useFormExitGuard } from '@saas-forge/react-shell';
import { useEffect, useRef, useState } from 'react';
import { useBlocker } from 'react-router';
import { platformMessages } from './messages';

type Props = {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly tenant: Tenant;
};

/** Tenant 信息与 Entitlement 分区独立读取；失败不会转换成无订阅或用量零。 */
export function TenantSubscriptionSection({ client, locale, tenant }: Props) {
  const translator = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const t = translator.translate.bind(translator);
  const [read, setRead] = useState<ConsoleApiResult<TenantSubscription>>();
  const [plans, setPlans] = useState<ConsoleApiResult<readonly Plan[]>>();
  const [guard, setGuard] = useState<'loading' | 'clear' | 'pending' | 'failed'>('loading');
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(true);
  const [planId, setPlanId] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [phase, setPhase] = useState<'editing' | 'submitting' | 'unknown'>('editing');
  const [problem, setProblem] = useState<string>();
  const pending = useRef<AbortController | null>(null);
  const dirty = phase === 'editing' && (planId !== '' || endsAt !== '');
  useFormExitGuard(dirty);
  const blocker = useBlocker(dirty);
  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );
  useEffect(() => {
    const controller = new AbortController();
    void client.getTenantSubscription(tenant.id, controller.signal).then((value) => {
      if (!controller.signal.aborted) {
        setRead(value);
        setBusy(false);
      }
    });
    async function readPlans() {
      let cursor: string | undefined;
      const visited = new Set<string>();
      const items: Plan[] = [];
      for (;;) {
        const page = await client.listPlans({
          status: 'ACTIVE',
          limit: 100,
          cursor,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        if (!page.ok) {
          setPlans(page);
          return;
        }
        items.push(...page.value.items.filter((plan) => plan.quotaLimits[0].limit >= 1));
        if (!page.value.hasMore) {
          setPlans({ ok: true, value: items });
          return;
        }
        if (page.value.nextCursor === null || visited.has(page.value.nextCursor)) {
          setPlans({ ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } });
          return;
        }
        cursor = page.value.nextCursor;
        visited.add(cursor);
      }
    }
    async function readGuard() {
      let cursor: string | undefined;
      const visited = new Set<string>();
      for (;;) {
        const page = await client.listSubscriptionOperations({
          tenantId: tenant.id,
          limit: 100,
          cursor,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        if (!page.ok) {
          setGuard('failed');
          return;
        }
        if (page.value.items.some((operation) => operation.state !== 'COMMITTED')) {
          setGuard('pending');
          return;
        }
        if (!page.value.hasMore) {
          setGuard('clear');
          return;
        }
        if (page.value.nextCursor === null || visited.has(page.value.nextCursor)) {
          setGuard('failed');
          return;
        }
        cursor = page.value.nextCursor;
        visited.add(cursor);
      }
    }
    void readPlans();
    void readGuard();
    return () => {
      controller.abort();
    };
  }, [client, tenant.id, revision]);

  function refresh() {
    setBusy(true);
    setPlans(undefined);
    setGuard('loading');
    setRevision((value) => value + 1);
  }
  const eligible =
    tenant.status === 'PENDING' &&
    (tenant.expiresAt === null || tenant.expiresAt.getTime() > Date.now());
  const canCreate =
    !busy &&
    read?.ok === true &&
    read.value.subscription === null &&
    eligible &&
    plans?.ok === true &&
    plans.value.some((plan) => plan.id === planId) &&
    guard === 'clear' &&
    phase === 'editing';
  return (
    <>
      <ContentPanel
        title={t('subscriptionTitle')}
        actions={
          <Button disabled={busy} onClick={refresh}>
            {t('subscriptionRetry')}
          </Button>
        }
      >
        {read === undefined ? (
          <p role="status">{t('tenantLoading')}</p>
        ) : !read.ok ? (
          <PersistentError title={t('subscriptionReadFailed')}>
            <p>{t('subscriptionReadHint')}</p>
            <p>{read.problem.code}</p>
          </PersistentError>
        ) : read.value.subscription === null ? (
          <p>{t('subscriptionAbsent')}</p>
        ) : (
          <DescriptionList
            items={[
              { label: t('subscriptionId'), value: read.value.subscription.id },
              { label: t('subscriptionPlan'), value: read.value.subscription.planId },
              { label: t('subscriptionStatus'), value: read.value.subscription.status },
              {
                label: t('subscriptionValidity'),
                value: (
                  <StatusTag tone={read.value.effective ? 'success' : 'warning'}>
                    {t(read.value.effective ? 'subscriptionEffective' : 'subscriptionExpired')}
                  </StatusTag>
                ),
              },
              {
                label: t('subscriptionStartsAt'),
                value: read.value.subscription.createdAt.toLocaleString(locale),
              },
              {
                label: t('subscriptionEndsAt'),
                value:
                  read.value.subscription.endsAt === null
                    ? t('tenantNoExpiry')
                    : read.value.subscription.endsAt.toLocaleString(locale),
              },
              { label: t('subscriptionLimit'), value: read.value.maxUsersLimit },
              { label: t('subscriptionUsed'), value: read.value.maxUsersUsed },
              {
                label: t('subscriptionObservedAt'),
                value: read.value.observedAt.toLocaleString(locale),
              },
            ]}
          />
        )}
        {read?.ok && read.value.subscription === null ? (
          <>
            {!eligible ? <p>{t('subscriptionIneligible')}</p> : null}
            {plans?.ok === false ? (
              <PersistentError title={t('subscriptionPlansFailed')}>
                <p>{plans.problem.code}</p>
              </PersistentError>
            ) : null}
            {guard === 'failed' ? (
              <PersistentError title={t('subscriptionRecoveryFailed')} />
            ) : null}
            {guard === 'pending' ? <p>{t('subscriptionPendingGuard')}</p> : null}
            <FormLayout
              ariaLabel={t('subscriptionCreate')}
              onSubmit={(event) => {
                event.preventDefault();
                if (!canCreate || pending.current) return;
                const end = endsAt === '' ? null : new Date(endsAt);
                if (
                  end !== null &&
                  (!/(?:Z|[+-]\d{2}:\d{2})$/.test(endsAt) ||
                    !Number.isFinite(end.getTime()) ||
                    end.getTime() <= Date.now())
                ) {
                  setInvalid(true);
                  return;
                }
                const controller = new AbortController();
                pending.current = controller;
                setPhase('submitting');
                void client
                  .createInitialSubscription({
                    tenantId: tenant.id,
                    request: { planId, endsAt: end },
                    signal: controller.signal,
                  })
                  .then((result) => {
                    if (controller.signal.aborted) return;
                    if (result.ok) {
                      pending.current = null;
                      setPlanId('');
                      setEndsAt('');
                      setPhase('editing');
                      refresh();
                    } else {
                      setPhase('unknown');
                      setProblem(result.problem.code);
                      refresh();
                    }
                  });
              }}
            >
              <SelectField
                id="subscription-plan"
                label={t('subscriptionPlan')}
                value={planId}
                onValueChange={setPlanId}
                disabled={
                  phase !== 'editing' ||
                  busy ||
                  !eligible ||
                  plans?.ok !== true ||
                  guard !== 'clear'
                }
                options={[
                  { value: '', label: t('subscriptionSelectPlan') },
                  ...(plans?.ok
                    ? plans.value.map((plan) => ({
                        value: plan.id,
                        label: `${plan.displayName} (${plan.code}) — ${String(plan.quotaLimits[0].limit)}`,
                      }))
                    : []),
                ]}
              />
              {plans?.ok && plans.value.length === 0 ? <p>{t('subscriptionNoPlans')}</p> : null}
              <TextField
                id="subscription-ends-at"
                label={t('subscriptionEndsAt')}
                value={endsAt}
                onValueChange={setEndsAt}
                disabled={phase !== 'editing' || !eligible || busy || guard !== 'clear'}
                placeholder="2027-01-01T00:00:00+08:00"
                error={invalid ? t('subscriptionDateInvalid') : undefined}
              />
              <p>{t('subscriptionExpiryHint')}</p>
              <Button type="submit" variant="primary" disabled={!canCreate}>
                {t(phase === 'submitting' ? 'tenantSaving' : 'subscriptionCreate')}
              </Button>
            </FormLayout>
          </>
        ) : null}
        {phase === 'unknown' ? (
          <WarningFeedback title={t('subscriptionUnknown')}>
            <p>{t('subscriptionUnknownHint')}</p>
            <p>{problem}</p>
          </WarningFeedback>
        ) : null}
      </ContentPanel>
      <SubscriptionRecoveryPanel
        client={client}
        tenantId={tenant.id}
        locale={locale}
        onView={() => {
          pending.current = null;
          setPhase('editing');
          setPlanId('');
          setEndsAt('');
          setProblem(undefined);
          refresh();
        }}
      />
      <UnsavedChangesDialog
        open={blocker.state === 'blocked'}
        onContinueEditing={() => {
          if (blocker.state === 'blocked') blocker.reset();
        }}
        onDiscard={() => {
          if (blocker.state === 'blocked') blocker.proceed();
        }}
      />
    </>
  );
}

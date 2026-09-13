import type { ConsoleApiResult } from '@saas-forge/app-runtime';
import { Button, ContentPanel, PersistentError } from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { shellMessages } from './messages';

interface RecoveryOperation {
  readonly id: string;
  readonly state: 'COMMITTED' | 'PROCESSING' | 'NOT_COMMITTED' | 'UNKNOWN';
  readonly createdAt: Date;
  readonly canReplay: boolean;
}
interface RecoveryPage<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}

const recoveryMessageKeys = {
  creation: {
    title: 'creationRecoveryTitle',
    hint: 'creationRecoveryHint',
    read: 'creationRecoveryRead',
    failure: 'creationRecoveryFailure',
    empty: 'creationRecoveryEmpty',
    unavailable: 'creationRecoveryExpired',
    view: 'creationRecoveryView',
    continue: 'creationRecoveryContinue',
    previous: 'creationRecoveryPrevious',
    next: 'creationRecoveryNext',
  },
  quota: {
    title: 'quotaRecoveryTitle',
    hint: 'quotaRecoveryHint',
    read: 'quotaRecoveryRead',
    failure: 'quotaRecoveryFailure',
    empty: 'quotaRecoveryEmpty',
    unavailable: 'quotaRecoveryUnavailable',
    view: 'quotaRecoveryView',
    continue: 'quotaRecoveryContinue',
    previous: 'quotaRecoveryPrevious',
    next: 'quotaRecoveryNext',
  },
  plan: {
    title: 'planRecoveryTitle',
    hint: 'planRecoveryHint',
    read: 'planRecoveryRead',
    failure: 'planRecoveryFailure',
    empty: 'planRecoveryEmpty',
    unavailable: 'planRecoveryUnavailable',
    view: 'planRecoveryView',
    continue: 'planRecoveryContinue',
    previous: 'planRecoveryPrevious',
    next: 'planRecoveryNext',
  },
  subscription: {
    title: 'subscriptionRecoveryTitle',
    hint: 'subscriptionRecoveryHint',
    read: 'subscriptionRecoveryRead',
    failure: 'subscriptionRecoveryFailure',
    empty: 'subscriptionRecoveryEmpty',
    unavailable: 'subscriptionRecoveryUnavailable',
    view: 'subscriptionRecoveryView',
    continue: 'subscriptionRecoveryContinue',
    previous: 'subscriptionRecoveryPrevious',
    next: 'subscriptionRecoveryNext',
  },
} as const;

/** 读取由 Runtime 管理的原操作者恢复记录；UI 不保存请求或恢复 Key。 */
export function OperationRecoveryPanel<T extends RecoveryOperation>({
  load,
  replay,
  label,
  resourceId,
  kind = 'creation',
  locale,
  onView,
}: {
  readonly load: (input: {
    cursor?: string;
    signal?: AbortSignal;
  }) => Promise<ConsoleApiResult<RecoveryPage<T>>>;
  readonly replay: (operation: T, signal?: AbortSignal) => Promise<ConsoleApiResult<T>>;
  readonly label: (operation: T) => string;
  readonly resourceId: (operation: T) => string | undefined;
  readonly kind?: 'creation' | 'quota' | 'plan' | 'subscription';
  readonly locale: SupportedLocale;
  readonly onView: (id: string) => void;
}) {
  const translate = createTranslator({
    namespace: '@saas-forge/react-shell',
    locale,
    messages: shellMessages,
  });
  const t = translate.translate.bind(translate);
  const messageKeys = recoveryMessageKeys[kind];
  const [result, setResult] = useState<ConsoleApiResult<RecoveryPage<T>>>();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string>();
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const request = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      request.current?.abort();
    },
    [],
  );

  async function read(nextCursors = cursors) {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setProblem(undefined);
    const next = await load({
      cursor: nextCursors.at(-1),
      signal: controller.signal,
    });
    if (controller.signal.aborted) return;
    setResult(next);
    setCursors(nextCursors);
    setBusy(false);
  }

  async function recover(operation: T) {
    if (busy) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    const next = await replay(operation, controller.signal);
    if (controller.signal.aborted) return;
    const id = next.ok ? resourceId(next.value) : undefined;
    if (next.ok && next.value.state === 'COMMITTED' && id !== undefined) {
      setBusy(false);
      onView(id);
      return;
    }
    setProblem(next.ok ? undefined : next.problem.code);
    setBusy(false);
    if (next.ok) await read();
  }

  return (
    <ContentPanel
      title={t(messageKeys.title)}
      description={t(messageKeys.hint)}
      actions={
        <Button
          disabled={busy}
          onClick={() => {
            void read();
          }}
        >
          {t(messageKeys.read)}
        </Button>
      }
    >
      {busy ? <p role="status">{t('creationRecoveryPending')}</p> : null}
      {problem !== undefined || result?.ok === false ? (
        <PersistentError title={t(messageKeys.failure)}>
          <p>{problem ?? (result?.ok === false ? result.problem.code : '')}</p>
        </PersistentError>
      ) : null}
      {result?.ok ? (
        <>
          {result.value.items.length === 0 ? (
            <p>{t(messageKeys.empty)}</p>
          ) : (
            <ul>
              {result.value.items.map((operation) => (
                <li key={operation.id}>
                  <strong>{label(operation)}</strong> — {t(`creationRecovery${operation.state}`)}
                  <p>{operation.createdAt.toLocaleString(locale)}</p>
                  {!operation.canReplay &&
                  operation.state !== 'COMMITTED' &&
                  operation.state !== 'PROCESSING' ? (
                    <p>{t(messageKeys.unavailable)}</p>
                  ) : null}
                  {operation.state === 'COMMITTED' && resourceId(operation) !== undefined ? (
                    <Button
                      onClick={() => {
                        const id = resourceId(operation);
                        if (id !== undefined) onView(id);
                      }}
                    >
                      {t(messageKeys.view)}
                    </Button>
                  ) : null}
                  {operation.state === 'NOT_COMMITTED' && operation.canReplay ? (
                    <Button
                      disabled={busy}
                      onClick={() => {
                        void recover(operation);
                      }}
                    >
                      {t(messageKeys.continue)}
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          <Button
            disabled={busy || cursors.length < 2}
            onClick={() => {
              void read(cursors.slice(0, -1));
            }}
          >
            {t(messageKeys.previous)}
          </Button>
          <Button
            disabled={busy || !result.value.hasMore}
            onClick={() => {
              if (result.value.nextCursor !== null)
                void read([...cursors, result.value.nextCursor]);
            }}
          >
            {t(messageKeys.next)}
          </Button>
        </>
      ) : null}
    </ContentPanel>
  );
}

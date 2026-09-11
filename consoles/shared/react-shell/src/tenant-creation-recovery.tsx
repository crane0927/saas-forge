import type {
  ConsoleApiClient,
  ConsoleApiResult,
  TenantCreationOperation,
  TenantCreationOperationPage,
} from '@saas-forge/app-runtime';
import { Button, ContentPanel, PersistentError } from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { shellMessages } from './messages';

/** 读取由 Runtime 管理的原操作者恢复记录；UI 不保存请求或恢复 Key。 */
export function TenantCreationRecoveryPanel({
  client,
  locale,
  onView,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly onView: (tenantId: string) => void;
}) {
  const translate = createTranslator({
    namespace: '@saas-forge/react-shell',
    locale,
    messages: shellMessages,
  });
  const t = translate.translate.bind(translate);
  const [result, setResult] = useState<ConsoleApiResult<TenantCreationOperationPage>>();
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
    const next = await client.listTenantCreations({
      cursor: nextCursors.at(-1),
      signal: controller.signal,
    });
    if (controller.signal.aborted) return;
    setResult(next);
    setCursors(nextCursors);
    setBusy(false);
  }

  async function recover(operation: TenantCreationOperation) {
    if (busy) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    const next = await client.recoverTenantCreation(operation, controller.signal);
    if (controller.signal.aborted) return;
    if (next.ok && next.value.state === 'COMMITTED' && next.value.tenantId !== undefined) {
      onView(next.value.tenantId);
      return;
    }
    setProblem(next.ok ? undefined : next.problem.code);
    setBusy(false);
    if (next.ok) await read();
  }

  return (
    <ContentPanel
      title={t('creationRecoveryTitle')}
      description={t('creationRecoveryHint')}
      actions={
        <Button
          disabled={busy}
          onClick={() => {
            void read();
          }}
        >
          {t('creationRecoveryRead')}
        </Button>
      }
    >
      {busy ? <p role="status">{t('creationRecoveryPending')}</p> : null}
      {problem !== undefined || result?.ok === false ? (
        <PersistentError title={t('creationRecoveryFailure')}>
          <p>{problem ?? (result?.ok === false ? result.problem.code : '')}</p>
        </PersistentError>
      ) : null}
      {result?.ok ? (
        <>
          {result.value.items.length === 0 ? (
            <p>{t('creationRecoveryEmpty')}</p>
          ) : (
            <ul>
              {result.value.items.map((operation) => (
                <li key={operation.id}>
                  <strong>{operation.displayName}</strong> —{' '}
                  {t(`creationRecovery${operation.state}`)}
                  <p>{operation.createdAt.toLocaleString(locale)}</p>
                  {!operation.canReplay &&
                  operation.state !== 'COMMITTED' &&
                  operation.state !== 'PROCESSING' ? (
                    <p>{t('creationRecoveryExpired')}</p>
                  ) : null}
                  {operation.state === 'COMMITTED' && operation.tenantId !== undefined ? (
                    <Button
                      onClick={() => {
                        if (operation.tenantId !== undefined) onView(operation.tenantId);
                      }}
                    >
                      {t('creationRecoveryView')}
                    </Button>
                  ) : null}
                  {operation.state === 'NOT_COMMITTED' && operation.canReplay ? (
                    <Button
                      disabled={busy}
                      onClick={() => {
                        void recover(operation);
                      }}
                    >
                      {t('creationRecoveryContinue')}
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
            {t('creationRecoveryPrevious')}
          </Button>
          <Button
            disabled={busy || !result.value.hasMore}
            onClick={() => {
              if (result.value.nextCursor !== null)
                void read([...cursors, result.value.nextCursor]);
            }}
          >
            {t('creationRecoveryNext')}
          </Button>
        </>
      ) : null}
    </ContentPanel>
  );
}

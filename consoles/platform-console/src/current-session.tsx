import type { ConsoleApiClient, ConsoleApiResult, CurrentSession } from '@saas-forge/app-runtime';
import { Button, PersistentError } from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { useEffect, useState } from 'react';

import { platformMessages } from './messages';

export function CurrentSessionPanel({
  client,
  locale,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
}) {
  const translate = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<ConsoleApiResult<CurrentSession>>();
  useEffect(() => {
    const controller = new AbortController();
    void client.getCurrentSession(controller.signal).then((next) => {
      if (!controller.signal.aborted) setResult(next);
    });
    return () => {
      controller.abort();
    };
  }, [client, attempt]);

  return (
    <section aria-labelledby="current-session-title">
      <h2 id="current-session-title">{translate.translate('currentSessionTitle')}</h2>
      {result === undefined ? (
        <p aria-live="polite">{translate.translate('currentSessionLoading')}</p>
      ) : result.ok ? (
        <dl>
          <dt>{translate.translate('identityId')}</dt>
          <dd>{result.value.identityId}</dd>
          <dt>{translate.translate('email')}</dt>
          <dd>{result.value.email}</dd>
          {result.value.displayName !== undefined ? (
            <>
              <dt>{translate.translate('displayName')}</dt>
              <dd>{result.value.displayName}</dd>
            </>
          ) : null}
          <dt>{translate.translate('platformAccess')}</dt>
          <dd>
            {translate.translate(result.value.platformAdmin ? 'platformAdmin' : 'noPlatformAccess')}
          </dd>
        </dl>
      ) : (
        <PersistentError title={translate.translate('currentSessionFailed')}>
          <p>
            {translate.translate(
              result.problem.status === 403 ? 'currentSessionDenied' : 'currentSessionRetryHint',
            )}
          </p>
          <p>{result.problem.code}</p>
        </PersistentError>
      )}
      <p>{translate.translate('currentSessionScope')}</p>
      <Button
        disabled={result === undefined}
        onClick={() => {
          setResult(undefined);
          setAttempt((value) => value + 1);
        }}
      >
        {translate.translate('currentSessionRetry')}
      </Button>
    </section>
  );
}

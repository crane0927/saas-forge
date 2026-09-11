import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { OperationRecoveryPanel } from './operation-recovery';
import { shellMessages } from './messages';

export function QuotaDefinitionRecoveryPanel({
  client,
  locale,
  onView,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly onView: (id: string) => void;
}) {
  const t = createTranslator({
    namespace: '@saas-forge/react-shell',
    locale,
    messages: shellMessages,
  });
  return (
    <OperationRecoveryPanel
      quota
      load={(input) => client.listQuotaDefinitionOperations(input)}
      replay={(operation, signal) => client.recoverQuotaDefinitionOperation(operation, signal)}
      label={(operation) =>
        t.translate(
          operation.operation === 'CREATE' ? 'quotaRecoveryCreate' : 'quotaRecoveryActivate',
        )
      }
      resourceId={(operation) => operation.quotaDefinitionId}
      locale={locale}
      onView={onView}
    />
  );
}

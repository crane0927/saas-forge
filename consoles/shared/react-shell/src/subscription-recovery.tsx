import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { OperationRecoveryPanel } from './operation-recovery';
import { shellMessages } from './messages';

export function SubscriptionRecoveryPanel({
  client,
  tenantId,
  locale,
  onView,
}: {
  readonly client: ConsoleApiClient;
  readonly tenantId: string;
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
      kind="subscription"
      load={(input) => client.listSubscriptionOperations({ ...input, tenantId })}
      replay={(operation, signal) => client.recoverSubscriptionOperation(operation, signal)}
      label={() => t.translate('subscriptionRecoveryCreate')}
      resourceId={(operation) => operation.subscriptionId}
      locale={locale}
      onView={onView}
    />
  );
}

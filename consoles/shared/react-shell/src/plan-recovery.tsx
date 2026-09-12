import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { OperationRecoveryPanel } from './operation-recovery';
import { shellMessages } from './messages';

export function PlanRecoveryPanel({
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
      plan
      load={(input) => client.listPlanOperations(input)}
      replay={(operation, signal) => client.recoverPlanOperation(operation, signal)}
      label={(operation) =>
        t.translate(
          operation.operation === 'CREATE' ? 'planRecoveryCreate' : 'planRecoveryActivate',
        )
      }
      resourceId={(operation) => operation.planId}
      locale={locale}
      onView={onView}
    />
  );
}

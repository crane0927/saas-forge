import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import type { SupportedLocale } from '@saas-forge/i18n';
import { OperationRecoveryPanel } from './operation-recovery';

export function TenantCreationRecoveryPanel({
  client,
  locale,
  onView,
}: {
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
  readonly onView: (tenantId: string) => void;
}) {
  return (
    <OperationRecoveryPanel
      load={(input) => client.listTenantCreations(input)}
      replay={(operation, signal) => client.recoverTenantCreation(operation, signal)}
      label={(operation) => operation.displayName}
      resourceId={(operation) => operation.tenantId}
      locale={locale}
      onView={onView}
    />
  );
}

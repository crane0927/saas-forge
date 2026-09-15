<script setup lang="ts">
import { OperationRecovery } from '@saas-forge/admin';
import type {
  TenantCreationOperation,
  PlanOperation,
  QuotaDefinitionOperation,
  SubscriptionOperation,
} from '@saas-forge/app-runtime';
import { usePlatform } from './use-platform';
const props = defineProps<{
  kind: 'tenant' | 'plan' | 'quota' | 'subscription';
  tenantId?: string;
}>();
const emit = defineEmits<{ view: [id: string] }>();
const { client, t } = usePlatform();
</script>
<template>
  <OperationRecovery
    v-if="kind === 'tenant'"
    :load="(input) => client.listTenantCreations(input)"
    :replay="
      (operation: TenantCreationOperation, signal: AbortSignal) =>
        client.recoverTenantCreation(operation, signal)
    "
    :label="(operation) => operation.displayName"
    :resource-id="(operation) => operation.tenantId"
    @view="emit('view', $event)"
  />
  <OperationRecovery
    v-else-if="kind === 'plan'"
    kind="plan"
    :load="(input) => client.listPlanOperations(input)"
    :replay="
      (operation: PlanOperation, signal: AbortSignal) =>
        client.recoverPlanOperation(operation, signal)
    "
    :label="(operation) => operation.operation"
    :resource-id="(operation) => operation.planId"
    @view="emit('view', $event)"
  />
  <OperationRecovery
    v-else-if="kind === 'quota'"
    kind="quota"
    :load="(input) => client.listQuotaDefinitionOperations(input)"
    :replay="
      (operation: QuotaDefinitionOperation, signal: AbortSignal) =>
        client.recoverQuotaDefinitionOperation(operation, signal)
    "
    :label="(operation) => operation.operation"
    :resource-id="(operation) => operation.quotaDefinitionId"
    @view="emit('view', $event)"
  />
  <OperationRecovery
    v-else
    kind="subscription"
    :load="(input) => client.listSubscriptionOperations({ ...input, tenantId: props.tenantId! })"
    :replay="
      (operation: SubscriptionOperation, signal: AbortSignal) =>
        client.recoverSubscriptionOperation(operation, signal)
    "
    :label="() => t('subscriptionCreate')"
    :resource-id="(operation) => operation.subscriptionId"
    @view="emit('view', $event)"
  />
</template>

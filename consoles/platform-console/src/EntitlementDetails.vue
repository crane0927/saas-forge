<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton, ElDrawer, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import { Problem, useRead, useMutation, readAll } from '@saas-forge/admin';
import type { Plan, QuotaDefinition, ConsoleApiResult } from '@saas-forge/app-runtime';
import { usePlatform } from './use-platform';
import { readPlanGuard } from './plan-guard';
import Recovery from './Recovery.vue';
const props = defineProps<{ kind: 'plan' | 'quota' }>();
const { client, t, locale } = usePlatform();
const route = useRoute();
const router = useRouter();
const id = String(route.params.id);
const base = props.kind === 'plan' ? '/plans' : '/quota-definitions';
const read = useRead<Plan | QuotaDefinition>((signal) =>
  props.kind === 'plan' ? client.getPlan(id, signal) : client.getQuotaDefinition(id, signal),
);
const guard = useRead(async (signal): Promise<ConsoleApiResult<boolean>> => {
  if (props.kind === 'plan') return readPlanGuard(client, signal, id);
  const result = await readAll(
    (cursor) => client.listQuotaDefinitionOperations({ cursor, limit: 100, signal }),
    signal,
  );
  return result.ok
    ? {
        ok: true,
        value: !result.value.some(
          (item) =>
            item.operation === 'ACTIVATE' &&
            item.quotaDefinitionId === id &&
            item.state !== 'COMMITTED',
        ),
      }
    : result;
});
const mutation = useMutation();
const quotaLimit = computed(() =>
  read.value.value && 'quotaLimits' in read.value.value
    ? read.value.value.quotaLimits[0]?.limit
    : undefined,
);
const canActivate = computed(
  () =>
    read.ready.value &&
    read.value.value?.status === 'DRAFT' &&
    (props.kind === 'quota' || (quotaLimit.value !== undefined && quotaLimit.value >= 1)) &&
    guard.ready.value &&
    guard.value.value &&
    !mutation.unknown.value,
);
function refresh() {
  read.refresh();
  guard.refresh();
}
async function activate() {
  if (!canActivate.value) return;
  const result = await mutation.run<Plan | QuotaDefinition>((signal) =>
    props.kind === 'plan'
      ? client.activatePlan({ id, signal })
      : client.activateQuotaDefinition({ id, signal }),
  );
  if (result?.ok) refresh();
}
function recovered(resource: string) {
  if (resource === id) {
    mutation.resolved();
    refresh();
  } else void router.replace(base + '/' + resource);
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t(kind === 'plan' ? 'planDetail' : 'quotaDetail')"
    class="console-drawer"
    :before-close="() => router.push(base)"
  >
    <Problem :code="read.problem.value || guard.problem.value || mutation.problem.value" />
    <ElDescriptions v-if="read.value.value" :column="1" border>
      <ElDescriptionsItem :label="t('tenantId')">{{ read.value.value.id }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('quotaName')">{{ read.value.value.code }}</ElDescriptionsItem>
      <ElDescriptionsItem v-if="'displayName' in read.value.value" :label="t('planDisplayName')">{{
        read.value.value.displayName
      }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('tenantStatus')">{{
        read.value.value.status
      }}</ElDescriptionsItem
      ><ElDescriptionsItem v-if="quotaLimit !== undefined" :label="t('planLimit')">{{
        quotaLimit
      }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('tenantCreatedAt')">{{
        read.value.value.createdAt.toLocaleString(locale)
      }}</ElDescriptionsItem>
    </ElDescriptions>
    <Problem
      v-if="quotaLimit !== undefined && quotaLimit < 1"
      :title="t('planLegacyZero')"
      warning
    />
    <Problem
      v-if="guard.ready.value && !guard.value.value"
      :title="t(kind === 'plan' ? 'planPendingGuard' : 'quotaPendingGuard')"
      warning
    />
    <div class="console-actions">
      <ElButton
        :disabled="read.loading.value || guard.loading.value || mutation.busy.value"
        @click="refresh"
        >{{ t('tenantRetry') }}</ElButton
      ><ElButton
        v-if="read.value.value?.status === 'DRAFT'"
        type="primary"
        :disabled="!canActivate"
        :loading="mutation.busy.value"
        @click="activate"
        >{{ t(kind === 'plan' ? 'planActivate' : 'quotaActivate') }}</ElButton
      >
    </div>
    <Recovery :kind="kind" @view="recovered" />
  </ElDrawer>
</template>

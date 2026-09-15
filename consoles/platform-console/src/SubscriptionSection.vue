<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElForm,
  ElFormItem,
  ElInput,
  ElSelect,
  ElOption,
} from 'element-plus';
import type { Tenant, TenantSubscription } from '@saas-forge/app-runtime';
import { useRead, useMutation, useFormExitGuard, readAll, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import Recovery from './Recovery.vue';
const props = defineProps<{
  tenant: Tenant;
  tenantKnown: boolean;
  subscription?: TenantSubscription;
  readProblem?: string;
  loading: boolean;
  revision: number;
}>();
const emit = defineEmits<{ changed: [] }>();
const { client, t, locale } = usePlatform();
const planId = ref('');
const endsAt = ref('');
const invalid = ref(false);
const mutation = useMutation();
const plans = useRead(
  (signal) =>
    readAll((cursor) => client.listPlans({ status: 'ACTIVE', limit: 100, cursor, signal }), signal),
  () => props.revision,
);
const guard = useRead(
  (signal) =>
    readAll(
      (cursor) =>
        client.listSubscriptionOperations({
          tenantId: props.tenant.id,
          limit: 100,
          cursor,
          signal,
        }),
      signal,
    ),
  () => props.revision,
);
const eligible = computed(
  () =>
    props.tenantKnown &&
    props.tenant.status === 'PENDING' &&
    (!props.tenant.expiresAt || props.tenant.expiresAt.getTime() > Date.now()),
);
const available = computed(
  () => plans.value.value?.filter((plan) => (plan.quotaLimits[0]?.limit ?? 0) >= 1) ?? [],
);
const allowed = computed(
  () =>
    eligible.value &&
    !props.loading &&
    !props.readProblem &&
    props.subscription?.subscription === null &&
    plans.ready.value &&
    available.value.some((plan) => plan.id === planId.value) &&
    guard.ready.value &&
    !guard.value.value?.some((item) => item.state !== 'COMMITTED') &&
    !mutation.unknown.value,
);
useFormExitGuard(
  () =>
    !mutation.busy.value && !mutation.unknown.value && (planId.value !== '' || endsAt.value !== ''),
);
async function submit() {
  if (!allowed.value) return;
  const end = endsAt.value === '' ? null : new Date(endsAt.value);
  invalid.value =
    end !== null &&
    (!/(?:Z|[+-]\d{2}:\d{2})$/.test(endsAt.value) ||
      !Number.isFinite(end.getTime()) ||
      end.getTime() <= Date.now());
  if (invalid.value) return;
  const result = await mutation.run((signal) =>
    client.createInitialSubscription({
      tenantId: props.tenant.id,
      request: { planId: planId.value, endsAt: end },
      signal,
    }),
  );
  if (result?.ok) {
    planId.value = '';
    endsAt.value = '';
  }
  emit('changed');
}
</script>
<template>
  <ElCard shadow="never"
    ><template #header
      ><h2>{{ t('subscriptionTitle') }}</h2></template
    >
    <Problem
      :code="readProblem || plans.problem.value || guard.problem.value || mutation.problem.value"
    />
    <ElDescriptions v-if="subscription?.subscription" :column="1" border>
      <ElDescriptionsItem :label="t('subscriptionId')">{{
        subscription.subscription.id
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionPlan')">{{
        subscription.subscription.planId
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionStatus')">{{
        subscription.subscription.status
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionValidity')">{{
        t(subscription.effective ? 'subscriptionEffective' : 'subscriptionExpired')
      }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('subscriptionStartsAt')">{{
        subscription.subscription.createdAt.toLocaleString(locale)
      }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('subscriptionEndsAt')">{{
        subscription.subscription.endsAt?.toLocaleString(locale) ?? t('tenantNoExpiry')
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionLimit')">{{
        subscription.maxUsersLimit
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionUsed')">{{
        subscription.maxUsersUsed
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('subscriptionObservedAt')">{{
        subscription.observedAt.toLocaleString(locale)
      }}</ElDescriptionsItem>
    </ElDescriptions>
    <ElForm
      v-else-if="subscription?.subscription === null"
      :aria-label="t('subscriptionCreate')"
      label-position="top"
      @submit.prevent="submit"
    >
      <p role="status">{{ t('subscriptionAbsent') }}</p>
      <Problem v-if="!eligible" :title="t('subscriptionIneligible')" warning />
      <ElFormItem :label="t('subscriptionPlan')"
        ><ElSelect
          id="subscription-plan"
          v-model="planId"
          :aria-label="t('subscriptionPlan')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          ><ElOption
            v-for="plan in available"
            :key="plan.id"
            :value="plan.id"
            :label="`${plan.displayName} (${plan.code}) — ${plan.quotaLimits[0]?.limit}`" /></ElSelect
      ></ElFormItem>
      <ElFormItem
        :label="t('subscriptionEndsAt')"
        :error="invalid ? t('subscriptionDateInvalid') : undefined"
        ><ElInput
          v-model="endsAt"
          :aria-label="t('subscriptionEndsAt')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          placeholder="2027-01-01T00:00:00+08:00"
      /></ElFormItem>
      <div class="console-actions">
        <ElButton
          native-type="submit"
          type="primary"
          :disabled="!allowed"
          :loading="mutation.busy.value"
          >{{ t('subscriptionCreate') }}</ElButton
        >
      </div></ElForm
    >
    <Problem
      v-if="guard.ready.value && guard.value.value?.some((item) => item.state !== 'COMMITTED')"
      :title="t('subscriptionPendingGuard')"
      warning
    />
    <div class="console-actions">
      <ElButton :disabled="loading || mutation.busy.value" @click="emit('changed')">{{
        t('subscriptionRetry')
      }}</ElButton>
    </div>
    <Recovery
      kind="subscription"
      :tenant-id="tenant.id"
      @view="
        mutation.resolved();
        emit('changed');
      "
    />
  </ElCard>
</template>

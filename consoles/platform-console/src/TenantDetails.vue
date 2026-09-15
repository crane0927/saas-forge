<script setup lang="ts">
import { PageHeading } from '@saas-forge/admin';
import { ref, computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTooltip } from 'element-plus';
import { Back } from '@element-plus/icons-vue';
import { useRead, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import SubscriptionSection from './SubscriptionSection.vue';
import InitializationSection from './InitializationSection.vue';
import NotificationSection from './NotificationSection.vue';
import LifecycleSection from './LifecycleSection.vue';
const { client, t, locale } = usePlatform();
const route = useRoute();
const router = useRouter();
const id = String(route.params.id);
const revision = ref(0);
const tenant = useRead(
  (signal) => client.getTenant(id, signal),
  () => revision.value,
  true,
);
const subscription = useRead(
  (signal) => client.getTenantSubscription(id, signal),
  () => revision.value,
  true,
);
const effective = computed(
  () =>
    tenant.ready.value && subscription.ready.value && subscription.value.value?.effective === true,
);
const refresh = () => {
  revision.value++;
};
</script>
<template>
  <PageHeading :title="t('tenantDetail')" />
  <div class="console-actions">
    <ElTooltip :content="t('tenantBack')"
      ><ElButton
        :icon="Back"
        :aria-label="t('tenantBack')"
        @click="router.push('/tenants')" /></ElTooltip
    ><ElButton :disabled="tenant.loading.value || subscription.loading.value" @click="refresh">{{
      t('tenantRetry')
    }}</ElButton>
  </div>
  <ElCard shadow="never"
    ><Problem :code="tenant.problem.value" /><ElDescriptions
      v-if="tenant.value.value"
      :column="1"
      border
    >
      <ElDescriptionsItem :label="t('tenantName')">{{
        tenant.value.value.displayName
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantId')">{{ id }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantStatus')">{{
        tenant.value.value.status
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantExpiresAt')">{{
        tenant.value.value.expiresAt?.toLocaleString(locale) ?? t('tenantNoExpiry')
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantCreatedAt')">{{
        tenant.value.value.createdAt.toLocaleString(locale)
      }}</ElDescriptionsItem>
    </ElDescriptions></ElCard
  >
  <SubscriptionSection
    v-if="tenant.value.value"
    :tenant="tenant.value.value"
    :tenant-known="tenant.ready.value"
    :subscription="subscription.value.value"
    :read-problem="subscription.problem.value"
    :loading="subscription.loading.value"
    :revision="revision"
    @changed="refresh"
  />
  <InitializationSection
    :id="id"
    :subscription-effective="effective"
    :revision="revision"
    @changed="refresh"
  />
  <NotificationSection :id="id" :revision="revision" />
  <LifecycleSection :id="id" :revision="revision" @changed="refresh" />
</template>

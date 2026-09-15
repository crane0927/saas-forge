<script setup lang="ts">
import { computed } from 'vue';
import { ElCard, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import { createTranslator } from '@saas-forge/i18n';
import { useConsole, useAuthenticationRuntime, PageHeading } from '@saas-forge/admin';
import { tenantMessages } from './messages';
const { runtime, locale } = useConsole();
const { state } = useAuthenticationRuntime(runtime);
const label = computed(() =>
  createTranslator({
    namespace: '@saas-forge/tenant-console-shell',
    locale: locale.value,
    messages: tenantMessages,
  }).translate('currentCompany'),
);
</script>
<template>
  <PageHeading
    id="tenant-workspace-title"
    :title="locale === 'zh-CN' ? 'Tenant 工作台' : 'Tenant workspace'"
  /><ElCard shadow="never"
    ><ElDescriptions :column="1" border
      ><ElDescriptionsItem :label="label">{{
        state.status === 'authenticated' ? state.tenantContext?.tenantDisplayName : ''
      }}</ElDescriptionsItem></ElDescriptions
    ></ElCard
  >
</template>

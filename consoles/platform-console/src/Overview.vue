<script setup lang="ts">
import { PageHeading } from '@saas-forge/admin';
import { ElButton, ElCard, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import { Problem, useRead } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
const { client, t } = usePlatform();
const read = useRead((signal) => client.getCurrentSession(signal));
</script>
<template>
  <PageHeading :title="t('platformOverviewTitle')" /><ElCard shadow="never"
    ><Problem :code="read.problem.value" /><ElDescriptions
      v-if="read.value.value"
      :column="1"
      border
      ><ElDescriptionsItem :label="t('identityId')">{{
        read.value.value.identityId
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('email')">{{ read.value.value.email }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('displayName')">{{
        read.value.value.displayName
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('platformAccess')">{{
        t(read.value.value.platformAdmin ? 'platformAdmin' : 'noPlatformAccess')
      }}</ElDescriptionsItem></ElDescriptions
    >
    <div class="console-actions">
      <ElButton :loading="read.loading.value" @click="read.refresh()">{{
        t('currentSessionRetry')
      }}</ElButton>
    </div></ElCard
  >
</template>

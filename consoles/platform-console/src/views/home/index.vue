<script setup lang="ts">
import { onMounted, onScopeDispose, ref, shallowRef } from 'vue';
import {
  ElCard,
  ElRow,
  ElCol,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElIcon,
  ElSkeleton,
} from 'element-plus';
import { useRouter } from 'vue-router';
import { useApplication } from '../../runtime/context';
import type { CurrentSession } from '@saas-forge/app-runtime';
import { $t } from '../../locales';
import { useAppStore } from '../../store/modules/app';
import Problem from '../../components/custom/Problem.vue';
import HeaderBanner from './modules/header-banner.vue';
const { runtime } = useApplication();
const router = useRouter();
const appStore = useAppStore();
const session = shallowRef<CurrentSession>();
const problem = ref<string>();
const loading = ref(false);
const heading = ref<HTMLElement>();
let controller: AbortController | undefined;
async function refresh() {
  if (loading.value) return;
  controller = new AbortController();
  loading.value = true;
  problem.value = undefined;
  try {
    const result = await runtime.client.getCurrentSession(controller.signal);
    if (controller.signal.aborted) return;
    if (result.ok) session.value = result.value;
    else problem.value = result.problem.code;
  } finally {
    if (!controller.signal.aborted) loading.value = false;
  }
}
onMounted(() => {
  heading.value?.focus();
  void refresh();
});
onScopeDispose(() => controller?.abort());
</script>
<template>
  <div class="flex-col-stretch gap-16px">
    <h1 ref="heading" class="sr-only" tabindex="-1">{{ $t('platformOverviewTitle') }}</h1>
    <HeaderBanner :name="session?.displayName || session?.email" />
    <ElRow :gutter="16">
      <ElCol :span="14"
        ><ElCard class="card-wrapper" :header="$t('sessionDetails')">
          <Problem :code="problem" />
          <ElSkeleton v-if="loading && !session" :rows="3" animated />
          <ElDescriptions v-if="session" :column="1" border>
            <ElDescriptionsItem :label="$t('identityId')">{{
              session.identityId
            }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="$t('email')">{{ session.email }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="$t('displayName')">{{
              session.displayName
            }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="$t('platformAccess')">{{
              $t(session.platformAdmin ? 'platformAdmin' : 'noPlatformAccess')
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <div class="console-actions">
            <ElButton :loading="loading" @click="refresh">{{ $t('currentSessionRetry') }}</ElButton>
          </div>
        </ElCard></ElCol
      >
      <ElCol :span="10"
        ><ElCard class="card-wrapper" :header="$t('quickLinks')"
          ><div class="grid grid-cols-2 gap-16px">
            <ElButton
              v-for="item in appStore.navigation.filter((item) => item.path !== '/')"
              :key="item.path"
              class="m-0! h-64px!"
              @click="router.push(item.path)"
              ><ElIcon class="mr-8px"><component :is="item.icon" /></ElIcon
              >{{ item.label }}</ElButton
            >
          </div></ElCard
        ></ElCol
      >
    </ElRow>
  </div>
</template>

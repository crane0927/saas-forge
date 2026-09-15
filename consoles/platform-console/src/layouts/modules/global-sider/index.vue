<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMenu, ElMenuItem, ElIcon, ElScrollbar } from 'element-plus';
import { useAppStore } from '../../../store/modules/app';
import { $t } from '../../../locales';
import GlobalLogo from '../global-logo/index.vue';
const appStore = useAppStore();
const route = useRoute();
const router = useRouter();
const current = computed(
  () =>
    appStore.navigation.find((item) => item.path !== '/' && route.path.startsWith(item.path))
      ?.path ?? '/',
);
</script>
<template>
  <aside class="size-full flex-col-stretch bg-container shadow-sider">
    <GlobalLogo :show-title="!appStore.siderCollapse" />
    <ElScrollbar class="flex-1 min-h-0">
      <nav :aria-label="$t('navigationLabel')">
        <ElMenu
          :default-active="current"
          :collapse="appStore.siderCollapse"
          :collapse-transition="false"
          class="border-0"
          @select="router.push($event)"
        >
          <ElMenuItem v-for="item in appStore.navigation" :key="item.path" :index="item.path"
            ><ElIcon><component :is="item.icon" /></ElIcon
            ><template #title>{{ item.label }}</template></ElMenuItem
          >
        </ElMenu>
      </nav>
    </ElScrollbar>
  </aside>
</template>

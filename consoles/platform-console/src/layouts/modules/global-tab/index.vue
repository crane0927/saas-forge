<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElIcon, ElScrollbar } from 'element-plus';
import { useAppStore } from '../../../store/modules/app';
import { useThemeStore } from '../../../store/modules/theme';
import PageTab from '../../../vendor/soybean/materials/libs/page-tab';
const route = useRoute();
const router = useRouter();
const appStore = useAppStore();
const theme = useThemeStore();
const visited = ref(['/']);
const current = computed(
  () =>
    appStore.navigation.find((item) => item.path !== '/' && route.path.startsWith(item.path))
      ?.path ?? '/',
);
watch(
  current,
  (path) => {
    if (!visited.value.includes(path)) visited.value.push(path);
  },
  { immediate: true },
);
const tabs = computed(() =>
  visited.value.flatMap((path) => appStore.navigation.filter((item) => item.path === path)),
);
</script>
<template>
  <ElScrollbar class="h-full bg-container shadow-tab">
    <div class="h-44px flex-y-center px-16px" role="navigation" aria-label="Tabs">
      <PageTab
        v-for="tab in tabs"
        :key="tab.path"
        mode="chrome"
        :active="current === tab.path"
        :dark-mode="theme.darkMode"
        :active-color="theme.darkMode ? '#93c5fd' : theme.themeColor"
        :closable="false"
        role="link"
        tabindex="0"
        :aria-current="current === tab.path ? 'page' : undefined"
        @click="router.push(tab.path)"
        @keydown.enter.prevent="router.push(tab.path)"
        @keydown.space.prevent="router.push(tab.path)"
        ><template #prefix
          ><ElIcon><component :is="tab.icon" /></ElIcon></template
        >{{ tab.label }}</PageTab
      >
    </div>
  </ElScrollbar>
</template>

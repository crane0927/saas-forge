<script setup lang="ts">
import { ElButton, ElBreadcrumb, ElBreadcrumbItem } from 'element-plus';
import { Fold, Expand, SwitchButton } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useAppStore } from '../../../store/modules/app';
import { $t } from '../../../locales';
import LangSwitch from '../../../components/common/LangSwitch.vue';
import ThemeSchemaSwitch from '../../../components/common/ThemeSchemaSwitch.vue';
defineProps<{ busy: boolean }>();
defineEmits<{ logout: [] }>();
const appStore = useAppStore();
const route = useRoute();
const current = computed(
  () =>
    appStore.navigation.find((item) => item.path !== '/' && route.path.startsWith(item.path)) ??
    appStore.navigation[0],
);
</script>
<template>
  <header class="h-full flex-y-center px-12px bg-container shadow-header">
    <ElButton
      text
      circle
      :icon="appStore.siderCollapse ? Expand : Fold"
      :aria-label="$t(appStore.siderCollapse ? 'expandMenu' : 'collapseMenu')"
      @click="appStore.toggleSiderCollapse"
    />
    <div class="h-full flex-y-center flex-1 min-w-0">
      <ElBreadcrumb class="ml-12px"
        ><ElBreadcrumbItem :to="current.path">{{ current.label }}</ElBreadcrumbItem></ElBreadcrumb
      >
    </div>
    <div class="h-full flex-y-center justify-end gap-8px">
      <LangSwitch /><ThemeSchemaSwitch /><ElButton
        text
        :icon="SwitchButton"
        :loading="busy"
        @click="$emit('logout')"
        >{{ $t('logout') }}</ElButton
      >
    </div>
  </header>
</template>

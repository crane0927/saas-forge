<script setup lang="ts">
import AdminLayout from '../../vendor/soybean/materials/libs/admin-layout';
import { useThemeStore } from '../../store/modules/theme';
import { useAppStore } from '../../store/modules/app';
import { $t } from '../../locales';
import GlobalHeader from '../modules/global-header/index.vue';
import GlobalSider from '../modules/global-sider/index.vue';
import GlobalTab from '../modules/global-tab/index.vue';
import GlobalContent from '../modules/global-content/index.vue';
import GlobalFooter from '../modules/global-footer/index.vue';
defineProps<{ busy: boolean }>();
defineEmits<{ logout: [] }>();
const themeStore = useThemeStore();
const appStore = useAppStore();
</script>
<template>
  <a href="#console-main" class="console-skip">{{ $t('skipContent') }}</a>
  <AdminLayout
    v-model:sider-collapse="appStore.siderCollapse"
    mode="vertical"
    scroll-mode="content"
    :fixed-top="true"
    :header-height="themeStore.header.height"
    :tab-visible="true"
    :tab-height="themeStore.tab.height"
    :sider-width="themeStore.sider.width"
    :sider-collapsed-width="themeStore.sider.collapsedWidth"
    :footer-visible="true"
    :footer-height="themeStore.footer.height"
    :fixed-footer="false"
    :right-footer="true"
  >
    <template #header><GlobalHeader :busy="busy" @logout="$emit('logout')" /></template>
    <template #sider><GlobalSider /></template>
    <template #tab><GlobalTab /></template>
    <slot />
    <GlobalContent />
    <template #footer><GlobalFooter /></template>
  </AdminLayout>
</template>

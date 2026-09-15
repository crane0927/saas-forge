<script setup lang="ts">
import {
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElIcon,
  ElMenu,
  ElMenuItem,
  ElScrollbar,
  ElTooltip,
} from 'element-plus';
import { Expand, Fold } from '@element-plus/icons-vue';
import type { Component } from 'vue';
import { ref } from 'vue';
import AdminLayout from '../vendor/soybean/materials/libs/admin-layout';

defineProps<{
  applicationName: string;
  logoUrl?: string;
  navigationLabel: string;
  skipLabel: string;
  collapseLabel: string;
  expandLabel: string;
  currentPath: string;
  pageTitle: string;
  items: readonly { path: string; label: string; icon: Component }[];
}>();
const emit = defineEmits<{ navigate: [path: string] }>();
const collapsed = ref(false);
</script>

<template>
  <a class="console-skip" href="#console-main">{{ skipLabel }}</a>
  <AdminLayout
    v-model:sider-collapse="collapsed"
    :tab-visible="false"
    :footer-visible="false"
    :header-height="56"
    :sider-width="220"
    :sider-collapsed-width="64"
    header-class="console-header"
    sider-class="console-sider"
    content-class="console-content"
  >
    <template #header>
      <div class="h-full flex items-center px-12px gap-12px">
        <ElTooltip :content="collapsed ? expandLabel : collapseLabel">
          <ElButton
            text
            :aria-label="collapsed ? expandLabel : collapseLabel"
            :icon="collapsed ? Expand : Fold"
            @click="collapsed = !collapsed"
          />
        </ElTooltip>
        <ElBreadcrumb class="flex-1 min-w-0"
          ><ElBreadcrumbItem>{{ pageTitle }}</ElBreadcrumbItem></ElBreadcrumb
        >
        <div class="console-actions"><slot name="actions" /></div>
      </div>
    </template>
    <template #sider>
      <div class="h-full flex flex-col">
        <div class="console-logo" :title="applicationName">
          <img
            v-if="logoUrl"
            :src="logoUrl"
            :alt="applicationName"
            width="32"
            height="32"
            class="mr-8px"
          /><span v-if="!collapsed">{{ applicationName }}</span>
        </div>
        <ElScrollbar class="flex-1">
          <nav :aria-label="navigationLabel">
            <ElMenu
              :default-active="currentPath"
              :collapse="collapsed"
              :collapse-transition="false"
              @select="emit('navigate', $event)"
            >
              <ElMenuItem v-for="item in items" :key="item.path" :index="item.path">
                <ElIcon><component :is="item.icon" /></ElIcon>
                <template #title>{{ item.label }}</template>
              </ElMenuItem>
            </ElMenu>
          </nav>
        </ElScrollbar>
      </div>
    </template>
    <div id="console-main" tabindex="-1" class="min-w-0 p-16px"><slot /></div>
  </AdminLayout>
</template>

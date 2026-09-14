<script setup lang="ts">
import { computed, ref } from 'vue';
import { OfficeBuilding, Key } from '@element-plus/icons-vue';
import { ElButton, ElCard, ElTable, ElTableColumn } from 'element-plus';
import { ConsoleLayout } from '../src';

const path = ref('/tenants');
const items = [
  { path: '/tenants', label: 'Tenant 管理', icon: OfficeBuilding },
  { path: '/plans', label: 'Plan 管理', icon: Key },
];
const title = computed(() => items.find((item) => item.path === path.value)!.label);
const rows = [{ id: 'fixture', displayName: '布局测试数据', status: '已启用' }];
</script>

<template>
  <ConsoleLayout
    application-name="SaaS Forge"
    navigation-label="全局导航"
    skip-label="跳转到主要内容"
    collapse-label="收起导航"
    expand-label="展开导航"
    :current-path="path"
    :page-title="title"
    :items="items"
    @navigate="path = $event"
  >
    <template #actions><ElButton>退出登录</ElButton></template>
    <ElCard shadow="never">
      <div class="console-actions">
        <ElButton>重置</ElButton><ElButton>查询</ElButton><ElButton type="primary">新增</ElButton>
      </div>
      <ElTable :data="rows" row-key="id"
        ><ElTableColumn prop="displayName" label="名称" /><ElTableColumn prop="status" label="状态"
      /></ElTable>
    </ElCard>
  </ConsoleLayout>
</template>

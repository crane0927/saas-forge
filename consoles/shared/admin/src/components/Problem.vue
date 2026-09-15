<script setup lang="ts">
import { ElAlert } from 'element-plus';
defineProps<{ code?: string; title?: string; warning?: boolean }>();
</script>
<template>
  <ElAlert
    v-if="code || title"
    :title="title || code"
    :description="title ? code : undefined"
    :type="warning ? 'warning' : 'error'"
    :closable="false"
    show-icon
    role="alert"
  />
</template>

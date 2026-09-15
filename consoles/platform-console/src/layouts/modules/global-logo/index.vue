<script setup lang="ts">
import { RouterLink } from 'vue-router';
import SystemLogo from '../../../components/custom/SystemLogo.vue';
defineProps<{ showTitle: boolean }>();
</script>
<template>
  <RouterLink to="/" class="h-56px flex-center gap-8px whitespace-nowrap overflow-hidden"
    ><SystemLogo class="size-32px" />
    <h2 v-if="showTitle" class="text-18px text-primary font-bold">SaaS Forge</h2></RouterLink
  >
</template>

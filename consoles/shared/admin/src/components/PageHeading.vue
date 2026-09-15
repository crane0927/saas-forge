<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
defineProps<{ title: string; visible?: boolean; id?: string }>();
const heading = ref<HTMLHeadingElement>();
onMounted(async () => {
  await nextTick();
  heading.value?.focus();
});
</script>
<template>
  <h1 :id="id" ref="heading" :class="{ 'sr-only': !visible }" tabindex="-1">{{ title }}</h1>
  <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ title }}</p>
</template>

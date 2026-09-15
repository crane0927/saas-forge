<script setup lang="ts">
import { RouterView } from 'vue-router';
</script>
<template>
  <main id="console-main" tabindex="-1" class="flex-grow bg-layout p-16px console-content">
    <RouterView v-slot="{ Component, route }"
      ><component
        :is="Component"
        :key="
          String(route.matched[0]?.name) +
          ':' +
          (route.matched.length > 1 ? '' : String(route.params.id ?? ''))
        "
    /></RouterView>
  </main>
</template>

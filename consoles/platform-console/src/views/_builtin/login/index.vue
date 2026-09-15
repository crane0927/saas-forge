<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { ElCard } from 'element-plus';
import { useThemeStore } from '../../../store/modules/theme';
import WaveBg from '../../../components/custom/wave-bg.vue';
import SystemLogo from '../../../components/custom/SystemLogo.vue';
import ThemeSchemaSwitch from '../../../components/common/ThemeSchemaSwitch.vue';
import LangSwitch from '../../../components/common/LangSwitch.vue';
const props = defineProps<{ title: string }>();
const themeStore = useThemeStore();
const bgColor = computed(
  () => `color-mix(in srgb, ${themeStore.themeColor} ${themeStore.darkMode ? 50 : 20}%, white)`,
);
const heading = ref<HTMLElement>();
watch(
  () => props.title,
  async () => {
    await nextTick();
    heading.value?.focus();
  },
  { immediate: true },
);
</script>
<template>
  <div
    class="relative size-full flex-center overflow-hidden login-page"
    :style="{ backgroundColor: bgColor }"
  >
    <WaveBg :theme-color="themeStore.themeColor" />
    <ElCard class="relative z-4 w-auto rd-12px">
      <div class="w-400px">
        <header class="flex-y-center justify-between">
          <SystemLogo class="size-64px" />
          <h2 class="text-28px text-primary font-500">SaaS Forge</h2>
          <div class="i-flex-col items-end"><ThemeSchemaSwitch /><LangSwitch /></div>
        </header>
        <main class="pt-24px">
          <h1 ref="heading" tabindex="-1" class="text-18px text-primary font-medium">
            {{ title }}
          </h1>
          <div class="pt-24px"><slot /></div>
        </main>
      </div>
    </ElCard>
  </div>
</template>

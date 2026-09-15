<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElPopover, ElRadioButton, ElRadioGroup } from 'element-plus';
import { useThemeStore } from '../../../store/modules/theme';
import { $t } from '../../../locales';
import SaasIcon from '../../../components/common/SaasIcon.vue';

const theme = useThemeStore();
const scheme = computed({
  get: () => theme.themeScheme,
  set: (value: 'light' | 'dark' | 'auto') => theme.setThemeScheme(value),
});
</script>

<template>
  <ElPopover placement="bottom-end" :width="220" trigger="click">
    <template #reference>
      <ElButton text circle :aria-label="$t('themeSettings')"><SaasIcon name="palette" /></ElButton>
    </template>
    <div class="theme-settings">
      <div class="theme-settings-title">{{ $t('themeSettings') }}</div>
      <ElRadioGroup v-model="scheme" size="small" :aria-label="$t('themeSettings')">
        <ElRadioButton value="light">{{ $t('themeLight') }}</ElRadioButton>
        <ElRadioButton value="dark">{{ $t('themeDark') }}</ElRadioButton>
        <ElRadioButton value="auto">{{ $t('themeSystem') }}</ElRadioButton>
      </ElRadioGroup>
    </div>
  </ElPopover>
</template>

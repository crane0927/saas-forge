<script setup lang="ts">
import { ElButton } from 'element-plus';
import { Sunny, Moon } from '@element-plus/icons-vue';
import { useThemeStore } from '../../store/modules/theme';
import { $t } from '../../locales';
const theme = useThemeStore();
</script>
<template>
  <ElButton
    text
    circle
    :icon="theme.darkMode ? Moon : Sunny"
    :aria-label="$t('themeSwitch')"
    @click="theme.toggleThemeScheme"
  />
</template>

<script setup lang="ts">
import { ElButton } from 'element-plus';
import { useThemeStore } from '../../store/modules/theme';
import { $t } from '../../locales';
import SaasIcon from './SaasIcon.vue';
const theme = useThemeStore();
</script>
<template>
  <ElButton text circle :aria-label="$t('themeSwitch')" @click="theme.toggleThemeScheme"
    ><SaasIcon :name="theme.darkMode ? 'moon' : 'sunny'"
  /></ElButton>
</template>

<script setup lang="ts">
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus';
import { locale, setLocale, type Locale } from '../../locales';
import { $t } from '../../locales';
import SaasIcon from './SaasIcon.vue';

function changeLocale(value: string | number | object) {
  if (value === 'zh-CN' || value === 'en-US') setLocale(value as Locale);
}
</script>

<template>
  <ElDropdown trigger="click" @command="changeLocale">
    <ElButton text circle :aria-label="$t('languageSwitch')"><SaasIcon name="language" /></ElButton>
    <template #dropdown>
      <ElDropdownMenu>
        <ElDropdownItem command="zh-CN" :disabled="locale === 'zh-CN'">简体中文</ElDropdownItem>
        <ElDropdownItem command="en-US" :disabled="locale === 'en-US'">English</ElDropdownItem>
      </ElDropdownMenu>
    </template>
  </ElDropdown>
</template>

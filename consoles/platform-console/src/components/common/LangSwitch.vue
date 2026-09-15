<script setup lang="ts">
import { ElSelect, ElOption } from 'element-plus';
import { locale, setLocale } from '../../locales';
</script>
<template>
  <ElSelect
    :model-value="locale"
    aria-label="Language / 语言"
    style="width: 110px"
    @update:model-value="setLocale"
    ><ElOption value="zh-CN" label="简体中文" /><ElOption value="en-US" label="English"
  /></ElSelect>
</template>

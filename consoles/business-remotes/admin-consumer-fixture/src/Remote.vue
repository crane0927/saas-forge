<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElAlert } from 'element-plus';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import { remoteMessages } from './locales';
const props = defineProps<{ locale: SupportedLocale }>();
const translator = computed(() =>
  createTranslator({
    namespace: '@saas-forge/admin-consumer-fixture',
    locale: props.locale,
    messages: remoteMessages,
  }),
);
const t = (key: keyof (typeof remoteMessages)['zh-CN'], values?: Record<string, string>) =>
  translator.value.translate(key, values);
const name = ref('Remote');
const submitted = ref<string>();
</script>
<template>
  <section data-testid="brand-remote">
    <h1>{{ t('pageTitle') }}</h1>
    <ElCard shadow="never"
      ><ElForm
        :aria-label="t('verificationFormLabel')"
        label-position="top"
        @submit.prevent="submitted = name.trim() || 'Remote'"
        ><ElFormItem :label="t('displayNameLabel')"
          ><ElInput v-model="name" :aria-label="t('displayNameLabel')"
        /></ElFormItem>
        <div class="console-actions">
          <ElButton native-type="submit" type="primary">{{ t('verifyFeedbackAction') }}</ElButton>
        </div></ElForm
      ><ElAlert
        v-if="submitted"
        :title="t('successMessage', { name: submitted })"
        type="success"
        :closable="false"
        role="status"
    /></ElCard>
  </section>
</template>

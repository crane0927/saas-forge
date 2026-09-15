<script setup lang="ts">
import { computed } from 'vue';
import { ElAlert } from 'element-plus';
import { $t } from '../../locales';
const props = defineProps<{ code?: string; title?: string }>();
const messageKeys = {
  AUTHENTICATION_FAILED: 'invalidCredentials',
  PASSWORD_TOO_SHORT: 'passwordTooShort',
  PASSWORD_TOO_LONG: 'passwordTooLong',
  PASSWORD_WHITESPACE_NOT_ALLOWED: 'passwordWhitespace',
  PASSWORD_COMPROMISED: 'passwordCompromised',
  RATE_LIMITED: 'rateLimited',
} as const;
const message = computed(
  () => props.title || $t(messageKeys[props.code as keyof typeof messageKeys] ?? 'unknownProblem'),
);
</script>
<template>
  <ElAlert
    v-if="code || title"
    :title="message"
    :description="code"
    :closable="false"
    type="error"
    show-icon
    role="alert"
  />
</template>

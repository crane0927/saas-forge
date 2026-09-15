<script setup lang="ts">
import { onScopeDispose, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElForm, ElFormItem, ElInput } from 'element-plus';
import { useConsole, useShellText } from '../runtime/context';
import Problem from '../components/Problem.vue';
const { runtime } = useConsole();
const t = useShellText();
const router = useRouter();
const props = defineProps<{ takeChallenge?: () => string | undefined }>();
let challenge = props.takeChallenge?.();
let controller: AbortController | undefined;
const password = ref('');
const state = ref<'ready' | 'pending' | 'success' | 'unknown' | 'failed'>(
  challenge ? 'ready' : 'failed',
);
const rejected = ref(false);
onScopeDispose(() => {
  controller?.abort();
  challenge = undefined;
  password.value = '';
});
async function submit() {
  if (controller || !challenge) return;
  const token = challenge;
  challenge = undefined;
  const newPassword = password.value;
  password.value = '';
  rejected.value = false;
  state.value = 'pending';
  controller = new AbortController();
  const result = await runtime.establishPassword({ token, newPassword, signal: controller.signal });
  if (controller.signal.aborted) return;
  controller = undefined;
  if (
    !result.ok &&
    result.problem.status === 400 &&
    [
      'PASSWORD_TOO_SHORT',
      'PASSWORD_TOO_LONG',
      'PASSWORD_WHITESPACE_NOT_ALLOWED',
      'PASSWORD_COMPROMISED',
    ].includes(result.problem.code)
  ) {
    challenge = token;
    state.value = 'ready';
    rejected.value = true;
    return;
  }
  state.value = result.ok
    ? 'success'
    : result.problem.status === undefined || result.problem.status >= 500
      ? 'unknown'
      : 'failed';
}
</script>
<template>
  <h1>{{ t('passwordSetupTitle') }}</h1>
  <p v-if="state === 'success'" role="status">{{ t('passwordSetupSuccess') }}</p>
  <Problem
    v-else-if="state === 'unknown' || state === 'failed'"
    :title="t(state === 'unknown' ? 'passwordSetupUnknown' : 'passwordSetupUnavailable')"
  />
  <ElForm v-else label-position="top" @submit.prevent="submit">
    <ElFormItem
      :label="t('newPasswordLabel')"
      :error="rejected ? t('passwordSetupPolicy') : undefined"
    >
      <ElInput
        v-model="password"
        type="password"
        autocomplete="new-password"
        :aria-label="t('newPasswordLabel')"
        :disabled="state === 'pending'"
        required
      />
    </ElFormItem>
    <div class="console-actions">
      <ElButton type="primary" native-type="submit" :loading="state === 'pending'">{{
        t('passwordSetupTitle')
      }}</ElButton>
    </div>
  </ElForm>
  <div class="console-actions">
    <ElButton
      @click="
        challenge = undefined;
        router.replace('/login');
      "
      >{{ t('signIn') }}</ElButton
    >
  </div>
</template>

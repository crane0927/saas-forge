<script setup lang="ts">
import { nextTick, onScopeDispose, ref, watch } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, ElSpace } from 'element-plus';
import { $t } from '../../../../locales';
const props = defineProps<{ changePassword: boolean; busy: boolean; problemCode?: string }>();
const emit = defineEmits<{ submit: [email: string, password: string] }>();
const email = ref('');
const password = ref('');
const passwordInput = ref<InstanceType<typeof ElInput>>();
watch(
  () => [props.busy, props.problemCode],
  async () => {
    if (!props.busy && props.problemCode) {
      await nextTick();
      passwordInput.value?.focus();
    }
  },
);
onScopeDispose(() => {
  password.value = '';
});
function handleSubmit() {
  if (props.busy) return;
  const secret = password.value;
  password.value = '';
  emit('submit', email.value, secret);
}
</script>
<template>
  <ElForm size="large" :show-label="false" @submit.prevent="handleSubmit">
    <ElFormItem v-if="!changePassword">
      <ElInput
        v-model="email"
        type="email"
        autocomplete="username"
        :placeholder="$t('emailLabel')"
        :aria-label="$t('emailLabel')"
        :disabled="busy"
        required
      />
    </ElFormItem>
    <ElFormItem>
      <ElInput
        ref="passwordInput"
        v-model="password"
        :aria-describedby="problemCode ? 'authentication-error' : undefined"
        type="password"
        :autocomplete="changePassword ? 'new-password' : 'current-password'"
        :placeholder="$t(changePassword ? 'newPasswordLabel' : 'passwordLabel')"
        :aria-label="$t(changePassword ? 'newPasswordLabel' : 'passwordLabel')"
        :disabled="busy"
        required
      />
    </ElFormItem>
    <ElSpace direction="vertical" :size="24" class="w-full" fill>
      <ElButton
        type="primary"
        size="large"
        round
        class="w-full"
        native-type="submit"
        :loading="busy"
        >{{ $t(changePassword ? 'passwordUpdate' : 'signIn') }}</ElButton
      >
    </ElSpace>
  </ElForm>
</template>

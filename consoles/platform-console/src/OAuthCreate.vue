<script setup lang="ts">
import { ref, onScopeDispose } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElDrawer, ElForm, ElFormItem, ElInput, ElSelect, ElOption } from 'element-plus';
import { Problem, useMutation, useFormExitGuard } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import SecretDisplay from './SecretDisplay.vue';
const { client, t, locale } = usePlatform();
const router = useRouter();
const name = ref('');
const scope = ref('read');
const secret = ref<string>();
const clientId = ref<string>();
const mutation = useMutation();
useFormExitGuard(
  () =>
    !clientId.value &&
    !mutation.busy.value &&
    !mutation.unknown.value &&
    (name.value !== '' || scope.value !== 'read'),
);
onScopeDispose(() => {
  secret.value = undefined;
});
async function submit() {
  if (!name.value.trim() || clientId.value) return;
  const allowedScopes = new Set<'runtime:read' | 'runtime:quota:write'>(
    scope.value === 'both'
      ? ['runtime:read', 'runtime:quota:write']
      : scope.value === 'write'
        ? ['runtime:quota:write']
        : ['runtime:read'],
  );
  const result = await mutation.run((signal) =>
    client.createOAuthClient({ request: { displayName: name.value, allowedScopes }, signal }),
  );
  if (result?.ok) {
    clientId.value = result.value.clientId;
    secret.value = result.value.clientSecret;
  } else if (
    result &&
    !result.ok &&
    (result.problem.status === 400 || result.problem.status === 403)
  ) {
    mutation.unknown.value = false;
  }
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t('oauthCreate')"
    class="console-drawer"
    :before-close="() => router.push('/oauth-clients')"
    ><Problem
      :code="mutation.problem.value"
      :title="mutation.unknown.value ? t('oauthUnknown') : undefined"
    />
    <ElForm v-if="!clientId" label-position="top" @submit.prevent="submit"
      ><ElFormItem :label="t('oauthName')"
        ><ElInput
          v-model="name"
          :aria-label="t('oauthName')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          required /></ElFormItem
      ><ElFormItem :label="t('oauthScopes')"
        ><ElSelect
          v-model="scope"
          :aria-label="t('oauthScopes')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          ><ElOption value="read" label="runtime:read" /><ElOption
            value="write"
            label="runtime:quota:write" /><ElOption
            value="both"
            :label="locale === 'zh-CN' ? '两者' : 'Both'" /></ElSelect
      ></ElFormItem>
      <div class="console-actions">
        <ElButton
          type="primary"
          native-type="submit"
          :loading="mutation.busy.value"
          :disabled="mutation.unknown.value"
          >{{ t('oauthCreate') }}</ElButton
        >
      </div></ElForm
    >
    <SecretDisplay v-if="secret" :secret="secret" @close="secret = undefined" />
    <div class="console-actions">
      <ElButton
        v-if="clientId"
        @click="
          secret = undefined;
          router.replace('/oauth-clients/' + clientId);
        "
        >{{ t('oauthView') }}</ElButton
      ><ElButton
        @click="
          secret = undefined;
          router.push('/oauth-clients/operations');
        "
        >{{ t('oauthOperations') }}</ElButton
      >
    </div>
  </ElDrawer>
</template>

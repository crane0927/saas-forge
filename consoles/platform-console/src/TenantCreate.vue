<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElDrawer, ElForm, ElFormItem, ElInput } from 'element-plus';
import { Problem, useFormExitGuard, useMutation } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import Recovery from './Recovery.vue';
const { client, t } = usePlatform();
const router = useRouter();
const name = ref('');
const invalid = ref(false);
const done = ref(false);
const mutation = useMutation();
useFormExitGuard(
  () => !done.value && !mutation.busy.value && !mutation.unknown.value && name.value !== '',
);
async function submit() {
  invalid.value = name.value.trim() === '' || name.value.length > 200;
  if (invalid.value) return;
  const result = await mutation.run((signal) =>
    client.createTenant({ request: { displayName: name.value }, signal }),
  );
  if (result?.ok) {
    done.value = true;
    await router.replace('/tenants/' + result.value.id);
  }
}
function close() {
  void router.push('/tenants');
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t('tenantCreate')"
    class="console-drawer"
    :before-close="close"
    destroy-on-close
  >
    <ElForm :aria-label="t('tenantCreate')" label-position="top" @submit.prevent="submit"
      ><ElFormItem :label="t('tenantName')" :error="invalid ? t('tenantNameError') : undefined"
        ><ElInput
          v-model="name"
          :aria-label="t('tenantName')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          required
          maxlength="200"
      /></ElFormItem>
      <div class="console-actions">
        <ElButton
          type="primary"
          native-type="submit"
          :loading="mutation.busy.value"
          :disabled="mutation.unknown.value"
          >{{ t('tenantCreate') }}</ElButton
        >
      </div></ElForm
    >
    <Problem
      :code="mutation.problem.value"
      :warning="mutation.unknown.value"
      :title="mutation.unknown.value ? t('tenantUnknown') : undefined"
    />
    <Recovery
      kind="tenant"
      @view="
        done = true;
        router.replace('/tenants/' + $event);
      "
    />
  </ElDrawer>
</template>

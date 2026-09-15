<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElDrawer, ElForm, ElFormItem, ElInput } from 'element-plus';
import { Problem, useRead, useMutation, useFormExitGuard } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import { readPlanGuard } from './plan-guard';
import Recovery from './Recovery.vue';
const { client, t } = usePlatform();
const router = useRouter();
const code = ref('');
const name = ref('');
const limit = ref('1');
const invalid = ref(false);
const done = ref(false);
const definition = useRead((signal) =>
  client.listQuotaDefinitions({ code: 'max_users', status: 'ACTIVE', signal }),
);
const quota = computed(() =>
  definition.value.value?.items.find(
    (item) => item.code === 'max_users' && item.status === 'ACTIVE',
  ),
);
const guard = useRead(
  (signal) => readPlanGuard(client, signal, undefined, code.value),
  () => code.value,
);
const mutation = useMutation();
useFormExitGuard(
  () =>
    !done.value &&
    !mutation.busy.value &&
    !mutation.unknown.value &&
    (code.value !== '' || name.value !== '' || limit.value !== '1'),
);
async function submit() {
  invalid.value = !(
    /^[a-z][a-z0-9-]{1,62}$/.test(code.value) &&
    name.value.trim().length > 0 &&
    name.value.length <= 200 &&
    /^\d+$/.test(limit.value) &&
    Number.isSafeInteger(Number(limit.value)) &&
    Number(limit.value) >= 1 &&
    Number(limit.value) <= 2147483647
  );
  if (
    invalid.value ||
    !quota.value ||
    !definition.ready.value ||
    !guard.ready.value ||
    !guard.value.value
  )
    return;
  const result = await mutation.run((signal) =>
    client.createPlan({
      request: {
        code: code.value,
        displayName: name.value,
        quotaLimits: [{ quotaDefinitionId: quota.value!.id, limit: Number(limit.value) }],
      },
      signal,
    }),
  );
  if (result?.ok) {
    done.value = true;
    await router.replace('/plans/' + result.value.id);
  }
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t('planCreate')"
    class="console-drawer"
    :before-close="() => router.push('/plans')"
  >
    <Problem :code="definition.problem.value || guard.problem.value" /><Problem
      v-if="definition.ready.value && !quota"
      :title="t('planDefinitionRequired')"
      warning
    />
    <Problem
      v-if="guard.ready.value && !guard.value.value"
      :title="t('planPendingGuard')"
      warning
    />
    <div class="console-actions">
      <ElButton
        v-if="!definition.ready.value || !guard.ready.value"
        @click="
          definition.refresh();
          guard.refresh();
        "
        >{{ t('planRetry') }}</ElButton
      >
    </div>
    <ElForm :aria-label="t('planCreate')" label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('planName')"
        ><ElInput
          v-model="code"
          :aria-label="t('planName')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          required
      /></ElFormItem>
      <ElFormItem :label="t('planDisplayName')"
        ><ElInput
          v-model="name"
          :aria-label="t('planDisplayName')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          required
          maxlength="200"
      /></ElFormItem>
      <ElFormItem :label="t('planLimit')" :error="invalid ? t('planInvalid') : undefined"
        ><ElInput
          v-model="limit"
          inputmode="numeric"
          :aria-label="t('planLimit')"
          :disabled="mutation.busy.value || mutation.unknown.value"
          required
      /></ElFormItem>
      <div class="console-actions">
        <ElButton
          type="primary"
          native-type="submit"
          :loading="mutation.busy.value"
          :disabled="mutation.unknown.value || !quota || !guard.ready.value || !guard.value.value"
          >{{ t('planCreate') }}</ElButton
        >
      </div> </ElForm
    ><Problem
      :code="mutation.problem.value"
      :title="mutation.unknown.value ? t('planUnknown') : undefined"
      warning
    />
    <Recovery
      kind="plan"
      @view="
        done = true;
        router.replace('/plans/' + $event);
      "
    />
  </ElDrawer>
</template>

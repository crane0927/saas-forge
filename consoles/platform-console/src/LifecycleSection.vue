<script setup lang="ts">
import { computed, ref, toRaw } from 'vue';
import { ElButton, ElCard, ElMessageBox } from 'element-plus';
import { useRead, useMutation, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
const props = defineProps<{ id: string; revision: number }>();
const emit = defineEmits<{ changed: [] }>();
const { client, t } = usePlatform();
const confirming = ref(false);
const read = useRead(
  (signal) => client.getTenantLifecycle(props.id, signal),
  () => props.revision,
);
const mutation = useMutation();
const stateText = computed(() => {
  const value = read.value.value;
  if (!value) return '';
  return t(
    value.state === 'PENDING'
      ? value.action === 'RESUME'
        ? 'lifecycleResumePending'
        : 'lifecycleSuspendPending'
      : value.state === 'RECOVERY_REQUIRED'
        ? 'lifecycleRecoveryRequired'
        : value.state === 'COMPLETED'
          ? value.action === 'RESUME'
            ? 'lifecycleResumed'
            : 'lifecycleSuspended'
          : value.state === 'RETRY_REQUIRED'
            ? 'lifecycleRetryRequired'
            : 'lifecycleReady',
  );
});
const labels = {
  SUSPEND: 'lifecycleSuspend',
  RESUME: 'lifecycleResume',
  RECOVER: 'lifecycleRecover',
} as const;
function allowed(action?: keyof typeof labels) {
  const value = read.value.value;
  return (
    read.ready.value &&
    value &&
    (action === 'SUSPEND'
      ? value.canSuspend
      : action === 'RESUME'
        ? value.canResume
        : action === 'RECOVER'
          ? value.canRecoverSuspension
          : value.canContinue)
  );
}
async function submit(action?: keyof typeof labels) {
  if (!allowed(action) || confirming.value || mutation.busy.value) return;
  if (action) {
    confirming.value = true;
    try {
      await ElMessageBox.confirm(
        t(action === 'RESUME' ? 'lifecycleResumeConsequence' : 'lifecycleSuspendConsequence') +
          ' ' +
          props.id,
        t(labels[action]),
        { type: 'warning' },
      );
    } catch {
      return;
    } finally {
      confirming.value = false;
    }
  }
  if (!allowed(action)) return;
  await mutation.run(
    (signal) =>
      action
        ? client.changeTenantLifecycle(props.id, action, undefined, signal)
        : client.continueTenantLifecycle(toRaw(read.value.value!), signal),
    true,
  );
  read.refresh();
  emit('changed');
}
</script>
<template>
  <ElCard shadow="never"
    ><template #header
      ><h2>{{ t('lifecycleTitle') }}</h2></template
    ><Problem
      :code="read.problem.value || mutation.problem.value"
      :title="mutation.unknown.value ? t('lifecycleUnconfirmed') : undefined"
    />
    <p v-if="read.value.value" role="status">{{ stateText }}</p>
    <p v-if="read.value.value?.operationId">
      {{ t('lifecycleReference', { reference: read.value.value.operationId }) }}
    </p>
    <div class="console-actions">
      <ElButton :disabled="read.loading.value || mutation.busy.value" @click="read.refresh()">{{
        t('oauthReload')
      }}</ElButton
      ><ElButton
        v-for="action in ['SUSPEND', 'RESUME', 'RECOVER'] as const"
        v-show="allowed(action)"
        :key="action"
        :disabled="confirming || mutation.busy.value"
        @click="submit(action)"
        >{{ t(labels[action]) }}</ElButton
      ><ElButton v-if="allowed()" :loading="mutation.busy.value" @click="submit()">{{
        t('lifecycleContinue')
      }}</ElButton>
    </div>
  </ElCard>
</template>

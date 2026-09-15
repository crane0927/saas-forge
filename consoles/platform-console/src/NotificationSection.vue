<script setup lang="ts">
import { watch, toRaw } from 'vue';
import { ElButton, ElCard } from 'element-plus';
import { useRead, useMutation, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
const props = defineProps<{ id: string; revision: number }>();
const { client, t } = usePlatform();
const read = useRead(
  (signal) => client.getTenantAdministratorPasswordSetup(props.id, signal),
  () => props.revision,
  true,
);
const mutation = useMutation();
const labels = {
  NOT_APPLICABLE: 'notificationNotApplicable',
  PENDING: 'notificationPending',
  MAIL_SERVICE_ACCEPTED: 'notificationAccepted',
  PASSWORD_READY: 'notificationPasswordReady',
  ACTION_REQUIRED: 'notificationActionRequired',
} as const;
watch(read.value, (value) => {
  if (value?.operationState === 'COMPLETED') mutation.resolved();
});
async function submit(recover = false) {
  if (
    !read.ready.value ||
    !read.value.value ||
    (recover ? !read.value.value.canContinue : !read.value.value.canResend)
  )
    return;
  await mutation.run(
    (signal) =>
      recover
        ? client.recoverTenantAdministratorPasswordSetup(toRaw(read.value.value!), signal)
        : client.resendTenantAdministratorPasswordSetup(props.id, signal),
    recover,
  );
  read.refresh();
}
</script>
<template>
  <ElCard shadow="never"
    ><template #header
      ><h2>{{ t('notificationTitle') }}</h2></template
    ><Problem
      :code="read.problem.value || mutation.problem.value"
      :title="mutation.unknown.value ? t('notificationUnknown') : undefined"
    />
    <p v-if="read.value.value" role="status">{{ t(labels[read.value.value.state]) }}</p>
    <div class="console-actions">
      <ElButton :disabled="read.loading.value || mutation.busy.value" @click="read.refresh()">{{
        t('notificationRefresh')
      }}</ElButton
      ><ElButton
        v-if="read.value.value?.canResend"
        :disabled="!read.ready.value || mutation.unknown.value"
        :loading="mutation.busy.value"
        @click="submit()"
        >{{ t('notificationResend') }}</ElButton
      ><ElButton
        v-if="read.value.value?.canContinue"
        :disabled="!read.ready.value"
        :loading="mutation.busy.value"
        @click="submit(true)"
        >{{ t('notificationContinue') }}</ElButton
      >
    </div>
  </ElCard>
</template>

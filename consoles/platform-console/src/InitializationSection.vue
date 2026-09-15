<script setup lang="ts">
import { computed, ref, toRaw } from 'vue';
import { ElButton, ElCard, ElForm, ElFormItem, ElInput } from 'element-plus';
import { useRead, useMutation, useFormExitGuard, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
const props = defineProps<{ id: string; subscriptionEffective: boolean; revision: number }>();
const emit = defineEmits<{ changed: [] }>();
const { client, t } = usePlatform();
const read = useRead(
  (signal) => client.getTenantAdministratorInitialization(props.id, signal),
  () => props.revision,
  true,
);
const mutation = useMutation();
const email = ref('');
const name = ref('');
const invalid = ref(false);
const labels = {
  NOT_STARTED: 'initializationNotStarted',
  PROCESSING: 'initializationProcessing',
  RECOVERY_REQUIRED: 'initializationRecoveryRequired',
  COMPENSATING: 'initializationCompensating',
  RETRY_REQUIRED: 'initializationRetryRequired',
  SUCCEEDED: 'initializationSucceeded',
  FAILED: 'initializationFailed',
} as const;
const allowed = computed(
  () =>
    read.ready.value &&
    read.value.value?.canStart &&
    props.subscriptionEffective &&
    !mutation.unknown.value &&
    !mutation.busy.value,
);
useFormExitGuard(
  () =>
    !mutation.busy.value && !mutation.unknown.value && (email.value !== '' || name.value !== ''),
);
async function submit(recover = false) {
  if (
    !read.ready.value ||
    !read.value.value ||
    (recover ? !read.value.value.canContinue : !allowed.value)
  )
    return;
  invalid.value =
    !recover &&
    (!/^[^\s@]+@[^\s@]+$/.test(email.value.trim()) ||
      email.value.trim().length > 320 ||
      name.value.trim().length > 200);
  if (invalid.value) return;
  const result = await mutation.run(
    (signal) =>
      recover
        ? client.recoverTenantAdministratorInitialization(toRaw(read.value.value!), signal)
        : client.initializeTenantAdministrator({
            tenantId: props.id,
            request: {
              administratorEmail: email.value.trim(),
              administratorDisplayName: name.value.trim() || undefined,
            },
            signal,
          }),
    recover,
  );
  email.value = '';
  name.value = '';
  if (result?.ok) emit('changed');
}
</script>
<template>
  <ElCard shadow="never"
    ><template #header
      ><h2>{{ t('initializationTitle') }}</h2></template
    ><Problem
      :code="read.problem.value || mutation.problem.value"
      :title="mutation.unknown.value ? t('initializationUnknown') : undefined"
    />
    <template v-if="read.value.value"
      ><p role="status">{{ t(labels[read.value.value.state]) }}</p>
      <p v-if="read.value.value.initialAdministratorMembershipId">
        {{ t('initializationMembership') }}: {{ read.value.value.initialAdministratorMembershipId }}
      </p>
      <Problem :code="read.value.value.failureCode" />
      <ElForm v-if="read.value.value.canStart" label-position="top" @submit.prevent="submit()"
        ><Problem
          v-if="!subscriptionEffective"
          :title="t('initializationSubscriptionRequired')"
          warning
        />
        <ElFormItem
          :label="t('initializationEmail')"
          :error="invalid ? t('initializationInputInvalid') : undefined"
          ><ElInput
            v-model="email"
            type="email"
            autocomplete="email"
            :aria-label="t('initializationEmail')"
            :disabled="!allowed"
            required /></ElFormItem
        ><ElFormItem :label="t('initializationName')"
          ><ElInput v-model="name" :aria-label="t('initializationName')" :disabled="!allowed"
        /></ElFormItem>
        <div class="console-actions">
          <ElButton
            native-type="submit"
            type="primary"
            :loading="mutation.busy.value"
            :disabled="!allowed || !email.trim()"
            >{{ t('initializationSubmit') }}</ElButton
          >
        </div></ElForm
      >
    </template>
    <div class="console-actions">
      <ElButton :disabled="read.loading.value || mutation.busy.value" @click="emit('changed')">{{
        t('initializationRefresh')
      }}</ElButton
      ><ElButton
        v-if="read.value.value?.canContinue"
        :disabled="!read.ready.value"
        :loading="mutation.busy.value"
        @click="submit(true)"
        >{{ t('initializationContinue') }}</ElButton
      ><ElButton
        v-if="
          read.ready.value && read.value.value?.state === 'RETRY_REQUIRED' && mutation.unknown.value
        "
        @click="mutation.resolved()"
        >{{ t('initializationNewAttempt') }}</ElButton
      >
    </div>
  </ElCard>
</template>

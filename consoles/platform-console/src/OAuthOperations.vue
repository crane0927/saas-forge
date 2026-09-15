<script setup lang="ts">
import { PageHeading } from '@saas-forge/admin';
import { ref, toRaw, onScopeDispose } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElCard, ElMessageBox } from 'element-plus';
import { Back } from '@element-plus/icons-vue';
import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { useRead, useMutation, Problem } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import SecretDisplay from './SecretDisplay.vue';
const { client, t, locale } = usePlatform();
const router = useRouter();
const cursor = ref<string>();
const secret = ref<string>();
const confirming = ref(false);
const mutation = useMutation();
const read = useRead(
  (signal) => client.listOAuthClientOperations(cursor.value, signal),
  () => cursor.value,
);
type Operation = Extract<
  Awaited<ReturnType<ConsoleApiClient['listOAuthClientOperations']>>,
  { ok: true }
>['value']['items'][number];
onScopeDispose(() => {
  secret.value = undefined;
});
async function recover(operation: Operation) {
  if (!operation.canRecover || secret.value || mutation.busy.value || confirming.value) return;
  confirming.value = true;
  try {
    await ElMessageBox.confirm(
      t('oauthRecoveryConsequence') + ' ' + operation.displayName,
      t('oauthRecoverSecret'),
      { type: 'warning' },
    );
  } catch {
    return;
  } finally {
    confirming.value = false;
  }
  const result = await mutation.run(
    (signal) => client.recoverOAuthClientOperation(toRaw(operation), signal),
    true,
  );
  if (result?.ok) secret.value = result.value.clientSecret;
  read.refresh();
}
</script>
<template>
  <PageHeading :title="t('oauthOperations')" />
  <div class="console-actions">
    <ElButton
      :icon="Back"
      :aria-label="t('tenantBack')"
      @click="router.push('/oauth-clients')"
    /><ElButton
      :disabled="read.loading.value || mutation.busy.value"
      @click="
        cursor = undefined;
        read.refresh();
      "
      >{{ t('oauthReload') }}</ElButton
    >
  </div>
  <Problem :code="read.problem.value || mutation.problem.value" />
  <p role="status">{{ t('oauthMissingOperationUnknown') }}</p>
  <SecretDisplay v-if="secret" :secret="secret" @close="secret = undefined" />
  <ElCard v-for="operation in read.value.value?.items" :key="operation.operationId" shadow="never"
    ><h2>{{ operation.displayName }}</h2>
    <p>
      {{ operation.clientId }} · {{ operation.action }} ·
      {{ operation.completedAt.toLocaleString(locale) }}
    </p>
    <p v-if="operation.recoveryUntil">
      {{ t('oauthRecoveryUntil', { time: operation.recoveryUntil.toLocaleString(locale) }) }}
    </p>
    <div class="console-actions">
      <ElButton
        v-if="operation.canRecover"
        :disabled="mutation.busy.value || confirming || !!secret"
        @click="recover(operation)"
        >{{ t('oauthRecoverSecret') }}</ElButton
      ><ElButton
        v-else-if="operation.action !== 'REVOKE'"
        :disabled="mutation.busy.value"
        @click="
          secret = undefined;
          router.push('/oauth-clients/new');
        "
        >{{ t('oauthCreateReplacement') }}</ElButton
      ><ElButton
        @click="
          secret = undefined;
          router.push('/oauth-clients/' + operation.clientId);
        "
        >{{ t('oauthView') }}</ElButton
      >
    </div></ElCard
  >
  <div class="console-actions">
    <ElButton
      :disabled="
        read.loading.value ||
        mutation.busy.value ||
        !read.value.value?.hasMore ||
        !read.value.value?.nextCursor
      "
      @click="cursor = read.value.value!.nextCursor ?? undefined"
      >{{ t('oauthOlderOperations') }}</ElButton
    >
  </div>
</template>

<script setup lang="ts">
import { ref, onScopeDispose } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton, ElDrawer, ElDescriptions, ElDescriptionsItem, ElMessageBox } from 'element-plus';
import { Problem, useRead, useMutation } from '@saas-forge/admin';
import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { usePlatform } from './use-platform';
import SecretDisplay from './SecretDisplay.vue';
const { client, t, locale } = usePlatform();
const route = useRoute();
const router = useRouter();
const id = String(route.params.id);
const read = useRead((signal) => client.getOAuthClient({ clientId: id, signal }));
const status = useRead((signal) => client.getOAuthClientCredentialStatus(id, signal));
const mutation = useMutation();
const secret = ref<string>();
const confirming = ref(false);
onScopeDispose(() => {
  secret.value = undefined;
});
function allowed(action: 'rotate' | 'revoke') {
  return (
    status.ready.value &&
    (action === 'rotate' ? status.value.value?.canRotate : status.value.value?.canRevoke) &&
    !mutation.busy.value &&
    !mutation.unknown.value &&
    !secret.value
  );
}
async function submit(action: 'rotate' | 'revoke') {
  if (!allowed(action) || confirming.value) return;
  confirming.value = true;
  try {
    if (action === 'revoke')
      await ElMessageBox.prompt(t('oauthRevokeConsequence') + ' ' + id, t('oauthRevoke'), {
        type: 'warning',
        inputValidator: (value) => value === id || id,
      });
    else
      await ElMessageBox.confirm(t('oauthRotateConsequence') + ' ' + id, t('oauthRotate'), {
        type: 'warning',
      });
  } catch {
    return;
  } finally {
    confirming.value = false;
  }
  if (!allowed(action)) return;
  const result = await mutation.run<
    | Extract<
        Awaited<ReturnType<ConsoleApiClient['rotateOAuthClientSecret']>>,
        { ok: true }
      >['value']
    | void
  >((signal) =>
    action === 'rotate'
      ? client.rotateOAuthClientSecret(id, signal)
      : client.revokeOAuthClient(id, signal),
  );
  if (result?.ok && result.value) secret.value = result.value.clientSecret;
  read.refresh();
  status.refresh();
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t('oauthDetail')"
    class="console-drawer"
    :before-close="() => router.push('/oauth-clients')"
    ><Problem
      :code="read.problem.value || status.problem.value || mutation.problem.value"
      :title="mutation.unknown.value ? t('oauthUnknown') : undefined"
    />
    <ElDescriptions v-if="read.value.value" :column="1" border
      ><ElDescriptionsItem :label="t('oauthName')">{{
        read.value.value.displayName
      }}</ElDescriptionsItem
      ><ElDescriptionsItem label="Client ID">{{ id }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('oauthType')">{{
        read.value.value.clientType
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('oauthScopes')">{{
        [...read.value.value.allowedScopes].join(', ')
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantStatus')">{{
        read.value.value.status
      }}</ElDescriptionsItem
      ><ElDescriptionsItem :label="t('tenantCreatedAt')">{{
        read.value.value.createdAt.toLocaleString(locale)
      }}</ElDescriptionsItem></ElDescriptions
    >
    <p v-if="read.value.value?.revokedAt">
      {{ t('oauthRevokedAt') }}: {{ read.value.value.revokedAt.toLocaleString(locale) }}
    </p>
    <p v-if="read.value.value?.revokedAt">
      {{ t('oauthRevokedAt') }}: {{ read.value.value.revokedAt.toLocaleString(locale) }}
    </p>
    <p v-if="status.value.value?.overlapEndsAt">
      {{
        t('oauthOverlapUntil', { time: status.value.value.overlapEndsAt.toLocaleString(locale) })
      }}
    </p>
    <SecretDisplay v-if="secret" :secret="secret" @close="secret = undefined" />
    <div class="console-actions">
      <ElButton
        :disabled="mutation.busy.value"
        @click="
          read.refresh();
          status.refresh();
        "
        >{{ t('oauthReload') }}</ElButton
      ><ElButton
        v-if="status.value.value?.canRotate"
        :disabled="!allowed('rotate') || confirming"
        @click="submit('rotate')"
        >{{ t('oauthRotate') }}</ElButton
      ><ElButton
        v-if="status.value.value?.canRevoke"
        type="danger"
        :disabled="!allowed('revoke') || confirming"
        @click="submit('revoke')"
        >{{ t('oauthRevoke') }}</ElButton
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

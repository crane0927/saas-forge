<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton, ElDrawer } from 'element-plus';
import { Problem, useRead, useMutation, readAll } from '@saas-forge/admin';
import { usePlatform } from './use-platform';
import Recovery from './Recovery.vue';
const { client, t } = usePlatform();
const router = useRouter();
const read = useRead((signal) => client.listQuotaDefinitions({ code: 'max_users', signal }));
const guard = useRead((signal) =>
  readAll((cursor) => client.listQuotaDefinitionOperations({ cursor, limit: 100, signal }), signal),
);
const mutation = useMutation();
const existing = computed(() => read.value.value?.items.find((item) => item.code === 'max_users'));
const allowed = computed(
  () =>
    read.ready.value &&
    !existing.value &&
    guard.ready.value &&
    !guard.value.value?.some((item) => item.state !== 'COMMITTED') &&
    !mutation.unknown.value,
);
async function create() {
  if (!allowed.value) return;
  const result = await mutation.run((signal) => client.createQuotaDefinition({ signal }));
  if (result?.ok) await router.replace('/quota-definitions/' + result.value.id);
}
</script>
<template>
  <ElDrawer
    :model-value="true"
    :title="t('quotaCreate')"
    class="console-drawer"
    :before-close="() => router.push('/quota-definitions')"
  >
    <Problem :code="read.problem.value || guard.problem.value || mutation.problem.value" />
    <p>max_users</p>
    <div class="console-actions">
      <ElButton
        @click="
          read.refresh();
          guard.refresh();
        "
        >{{ t('quotaRetry') }}</ElButton
      ><ElButton
        v-if="existing"
        type="primary"
        @click="router.replace('/quota-definitions/' + existing.id)"
        >{{ t('quotaReuse') }}</ElButton
      ><ElButton
        v-else
        type="primary"
        :disabled="!allowed"
        :loading="mutation.busy.value"
        @click="create"
        >{{ t('quotaCreate') }}</ElButton
      >
    </div>
    <Problem
      v-if="guard.ready.value && guard.value.value?.some((item) => item.state !== 'COMMITTED')"
      :title="t('quotaPendingGuard')"
      warning
    />
    <Recovery kind="quota" @view="router.replace('/quota-definitions/' + $event)" />
  </ElDrawer>
</template>

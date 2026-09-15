<script setup lang="ts">
import { ref, watch } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import {
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElSelect,
  ElOption,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { Problem, useRead, PageHeading } from '@saas-forge/admin';
import type {
  ConsoleApiResult,
  TenantStatus,
  PlanStatus,
  QuotaDefinitionStatus,
  OAuthClientStatus,
  OAuthClientType,
} from '@saas-forge/app-runtime';
import { usePlatform } from './use-platform';
import Recovery from './Recovery.vue';
const props = defineProps<{ kind: 'tenant' | 'plan' | 'quota' | 'oauth' }>();
const { client, t, locale } = usePlatform();
const router = useRouter();
const route = useRoute();
const config = {
  tenant: {
    path: '/tenants',
    name: 'tenantName',
    create: 'tenantCreate',
    states: {
      PENDING: 'tenantPending',
      ACTIVE: 'tenantActive',
      SUSPENDED: 'tenantSuspended',
      CLOSED: 'tenantClosed',
    },
  },
  plan: {
    path: '/plans',
    name: 'planName',
    create: 'planCreate',
    states: { DRAFT: 'planDraft', ACTIVE: 'planActive', RETIRED: 'planRetired' },
  },
  quota: {
    path: '/quota-definitions',
    name: 'quotaName',
    create: 'quotaCreate',
    states: { DRAFT: 'quotaDraft', ACTIVE: 'quotaActive', RETIRED: 'quotaRetired' },
  },
  oauth: {
    path: '/oauth-clients',
    name: 'oauthName',
    create: 'oauthCreate',
    states: { ACTIVE: 'oauthActive', REVOKED: 'oauthRevoked' },
  },
} as const;
const settings = config[props.kind];
const name = ref('');
const status = ref('');
const clientType = ref<OAuthClientType | ''>('');
const query = ref({ name: '', status: '', clientType: '' as OAuthClientType | '' });
const cursors = ref<(string | undefined)[]>([undefined]);
type Row = { id: string; label: string; status: string; code?: string };
const read = useRead(
  async (
    signal,
  ): Promise<ConsoleApiResult<{ items: Row[]; hasMore: boolean; nextCursor: string | null }>> => {
    const input = { limit: 50, cursor: cursors.value.at(-1), signal };
    const page =
      props.kind === 'tenant'
        ? await client.listTenants({
            ...input,
            name: query.value.name || undefined,
            status: (query.value.status as TenantStatus) || undefined,
          })
        : props.kind === 'plan'
          ? await client.listPlans({
              ...input,
              code: query.value.name || undefined,
              status: (query.value.status as PlanStatus) || undefined,
            })
          : props.kind === 'quota'
            ? await client.listQuotaDefinitions({
                ...input,
                code: query.value.name || undefined,
                status: (query.value.status as QuotaDefinitionStatus) || undefined,
              })
            : await client.listOAuthClients({
                ...input,
                name: query.value.name || undefined,
                status: (query.value.status as OAuthClientStatus) || undefined,
                clientType: query.value.clientType || undefined,
              });
    if (!page.ok) return page;
    return {
      ok: true,
      value: {
        ...page.value,
        nextCursor: page.value.nextCursor ?? null,
        items: page.value.items.map((row) => ({
          id: 'clientId' in row ? row.clientId : row.id,
          label: 'displayName' in row ? row.displayName : row.code,
          status: row.status,
          ...('code' in row ? { code: row.code } : {}),
        })),
      },
    };
  },
  () => [query.value, cursors.value],
);
function search(reset = false) {
  if (reset) {
    name.value = '';
    status.value = '';
    clientType.value = '';
  }
  cursors.value = [undefined];
  query.value = { name: name.value, status: status.value, clientType: clientType.value };
}
watch(
  () => route.path,
  (path) => {
    if (path === settings.path) read.refresh();
  },
);
const statusText = (value: string) => {
  const key = Object.entries(settings.states).find(([state]) => state === value)?.[1];
  return key ? t(key) : value;
};
</script>
<template>
  <PageHeading
    :title="
      t(
        kind === 'tenant'
          ? 'tenantsTitle'
          : kind === 'plan'
            ? 'planDefinitionsTitle'
            : kind === 'quota'
              ? 'quotaDefinitionsTitle'
              : 'oauthClientsTitle',
      )
    "
  />
  <ElCard shadow="never"
    ><ElForm class="console-filter" @submit.prevent="search()">
      <ElFormItem :label="t(settings.name)"
        ><ElInput v-model="name" :aria-label="t(settings.name)" clearable
      /></ElFormItem>
      <ElFormItem :label="t('tenantStatus')"
        ><ElSelect v-model="status" :aria-label="t('tenantStatus')" clearable
          ><ElOption :value="''" :label="t('tenantAllStates')" /><ElOption
            v-for="(key, value) in settings.states"
            :key="value"
            :value="value"
            :label="t(key)" /></ElSelect
      ></ElFormItem>
      <ElFormItem v-if="kind === 'oauth'" :label="t('oauthType')"
        ><ElSelect v-model="clientType" :aria-label="t('oauthType')" clearable
          ><ElOption value="RUNTIME_SERVICE" :label="t('oauthRuntime')" /><ElOption
            value="RESERVED_SERVICE"
            :label="t('oauthReserved')" /></ElSelect
      ></ElFormItem>
      <div class="console-actions">
        <ElButton :disabled="read.loading.value" @click="search(true)">{{
          locale === 'zh-CN' ? '重置' : 'Reset'
        }}</ElButton
        ><ElButton native-type="submit" :loading="read.loading.value">{{
          locale === 'zh-CN' ? '查询' : 'Search'
        }}</ElButton
        ><ElButton type="primary" :icon="Plus" @click="router.push(settings.path + '/new')">{{
          t(settings.create)
        }}</ElButton>
      </div>
    </ElForm></ElCard
  >
  <ElCard shadow="never"
    ><Problem :code="read.problem.value" /><ElTable
      v-loading="read.loading.value"
      role="region"
      table-layout="auto"
      :data="read.value.value?.items ?? []"
      row-key="id"
      :aria-label="t(settings.name)"
    >
      <ElTableColumn
        prop="label"
        :label="t(kind === 'plan' ? 'planDisplayName' : settings.name)"
        min-width="180"
      />
      <ElTableColumn v-if="kind === 'plan'" prop="code" :label="t('planName')" />
      <ElTableColumn :label="t('tenantStatus')"
        ><template #default="{ row }"
          ><ElTag
            :type="
              row.status === 'ACTIVE'
                ? 'success'
                : row.status === 'DRAFT' || row.status === 'PENDING'
                  ? 'warning'
                  : 'info'
            "
            >{{ statusText(row.status) }}</ElTag
          ></template
        ></ElTableColumn
      >
      <ElTableColumn align="right" width="140"
        ><template #default="{ row }"
          ><ElButton text type="primary" @click="router.push(settings.path + '/' + row.id)">{{
            t('tenantView')
          }}</ElButton></template
        ></ElTableColumn
      >
    </ElTable>
    <div class="console-actions">
      <ElButton
        :disabled="read.loading.value || cursors.length < 2"
        @click="cursors = cursors.slice(0, -1)"
        >{{ locale === 'zh-CN' ? '上一页' : 'Previous' }}</ElButton
      ><ElButton
        :disabled="
          read.loading.value || !read.value.value?.hasMore || !read.value.value?.nextCursor
        "
        @click="cursors = [...cursors, read.value.value!.nextCursor!]"
        >{{ locale === 'zh-CN' ? '下一页' : 'Next' }}</ElButton
      >
    </div></ElCard
  >
  <Recovery
    v-if="kind !== 'oauth'"
    :kind="kind"
    @view="router.push(settings.path + '/' + $event)"
  />
  <div v-else class="console-actions">
    <ElButton @click="router.push('/oauth-clients/operations')">{{
      t('oauthOperations')
    }}</ElButton>
  </div>
  <RouterView v-slot="{ Component }"
    ><component :is="Component" :key="route.fullPath"
  /></RouterView>
</template>

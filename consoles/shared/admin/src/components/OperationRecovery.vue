<script
  setup
  lang="ts"
  generic="
    T extends {
      id: string;
      state: 'COMMITTED' | 'PROCESSING' | 'NOT_COMMITTED' | 'UNKNOWN';
      createdAt: Date;
      canReplay: boolean;
    }
  "
>
import { onScopeDispose, ref, shallowRef, toRaw } from 'vue';
import { ElButton, ElCard, ElTable, ElTableColumn, ElTag } from 'element-plus';
import type { ConsoleApiResult } from '@saas-forge/app-runtime';
import { computed } from 'vue';
import { createTranslator } from '@saas-forge/i18n';
import { recoveryMessages } from '../messages/recovery';
import { useConsole } from '../runtime/context';
import Problem from './Problem.vue';
const props = withDefaults(
  defineProps<{
    kind?: 'creation' | 'plan' | 'quota' | 'subscription';
    load: (input: {
      cursor?: string;
      signal: AbortSignal;
    }) => Promise<
      ConsoleApiResult<{ items: readonly T[]; hasMore: boolean; nextCursor: string | null }>
    >;
    replay: (operation: T, signal: AbortSignal) => Promise<ConsoleApiResult<T>>;
    label: (operation: T) => string;
    resourceId: (operation: T) => string | null | undefined;
  }>(),
  { kind: 'creation' },
);
const emit = defineEmits<{ view: [id: string] }>();
const { locale } = useConsole();
const translator = computed(() =>
  createTranslator({
    namespace: '@saas-forge/admin/recovery',
    locale: locale.value,
    messages: recoveryMessages,
  }),
);
const t = (key: keyof (typeof recoveryMessages)['zh-CN']) => translator.value.translate(key);
const rows = shallowRef<readonly T[]>();
const nextCursor = ref<string | null>(null);
const cursors = ref<(string | undefined)[]>([undefined]);
const busy = ref(false);
const problem = ref<string>();
let controller: AbortController | undefined;
onScopeDispose(() => controller?.abort());
const operationText = (row: T) => {
  const value = props.label(row);
  return (props.kind === 'plan' || props.kind === 'quota') &&
    (value === 'CREATE' || value === 'ACTIVATE')
    ? t(`${props.kind}Recovery${value === 'CREATE' ? 'Create' : 'Activate'}`)
    : value;
};
const stateText = (state: T['state']) => t(`creationRecovery${state}`);
const text = (suffix: 'Title' | 'Read' | 'Continue' | 'View' | 'Empty' | 'Previous' | 'Next') =>
  t(`${props.kind}Recovery${suffix}`);
async function read(next = cursors.value) {
  if (busy.value) return;
  controller = new AbortController();
  busy.value = true;
  problem.value = undefined;
  try {
    const result = await props.load({ cursor: next.at(-1), signal: controller.signal });
    if (controller.signal.aborted) return;
    if (result.ok) {
      rows.value = result.value.items;
      cursors.value = next;
      nextCursor.value = result.value.hasMore ? result.value.nextCursor : null;
      if (result.value.hasMore && !result.value.nextCursor)
        problem.value = 'INVALID_SERVICE_RESPONSE';
    } else problem.value = result.problem.code;
  } catch {
    if (!controller.signal.aborted) problem.value = 'NETWORK_UNAVAILABLE';
  } finally {
    if (!controller.signal.aborted) {
      busy.value = false;
      controller = undefined;
    }
  }
}
async function recover(row: T) {
  if (busy.value || row.state !== 'NOT_COMMITTED' || !row.canReplay) return;
  controller = new AbortController();
  busy.value = true;
  problem.value = undefined;
  let committedId: string | null | undefined;
  try {
    const result = await props.replay(toRaw(row), controller.signal);
    if (controller.signal.aborted) return;
    if (!result.ok) {
      problem.value = result.problem.code;
      return;
    }
    if (result.value.state === 'COMMITTED') committedId = props.resourceId(result.value);
  } catch {
    if (!controller.signal.aborted) problem.value = 'NETWORK_UNAVAILABLE';
    return;
  } finally {
    if (!controller.signal.aborted) {
      busy.value = false;
      controller = undefined;
    }
  }
  if (committedId) emit('view', committedId);
  else await read();
}
</script>
<template>
  <ElCard shadow="never">
    <template #header
      ><div class="flex items-center justify-between gap-16px">
        <h2>{{ text('Title') }}</h2>
        <ElButton :loading="busy" @click="read()">{{ text('Read') }}</ElButton>
      </div></template
    >
    <Problem :code="problem" />
    <p v-if="rows?.length === 0" role="status">{{ text('Empty') }}</p>
    <ElTable v-else-if="rows" :data="[...rows]" row-key="id" :aria-label="text('Title')">
      <ElTableColumn :label="text('Title')"
        ><template #default="{ row }"
          >{{ operationText(row) }} · {{ row.createdAt.toLocaleString(locale) }}</template
        ></ElTableColumn
      >
      <ElTableColumn
        ><template #default="{ row }"
          ><ElTag>{{ stateText(row.state) }}</ElTag></template
        ></ElTableColumn
      >
      <ElTableColumn align="right"
        ><template #default="{ row }"
          ><ElButton
            v-if="row.state === 'COMMITTED' && resourceId(row)"
            :disabled="busy"
            @click="emit('view', resourceId(row)!)"
            >{{ text('View') }}</ElButton
          ><ElButton
            v-else-if="row.state === 'NOT_COMMITTED' && row.canReplay"
            :disabled="busy"
            @click="recover(row)"
            >{{ text('Continue') }}</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <div v-if="rows" class="console-actions">
      <ElButton :disabled="busy || cursors.length < 2" @click="read(cursors.slice(0, -1))">{{
        text('Previous')
      }}</ElButton
      ><ElButton :disabled="busy || !nextCursor" @click="read([...cursors, nextCursor!])">{{
        text('Next')
      }}</ElButton>
    </div>
  </ElCard>
</template>

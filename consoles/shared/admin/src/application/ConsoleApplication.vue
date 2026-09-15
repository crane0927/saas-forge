<script setup lang="ts">
import {
  computed,
  onErrorCaptured,
  onMounted,
  onScopeDispose,
  provide,
  ref,
  shallowRef,
  type Component,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton, ElConfigProvider, ElMessageBox, ElSelect, ElOption } from 'element-plus';
import zhCN from 'element-plus/es/locale/lang/zh-cn';
import enUS from 'element-plus/es/locale/lang/en';
import {
  createRuntimeConfigBootstrap,
  createAuthenticationRuntimeAfterConfig,
  type AuthenticationIntent,
  type AuthenticationRuntime,
} from '@saas-forge/app-runtime';
import { supportedLocaleRegistry, type SupportedLocale } from '@saas-forge/i18n';
import { consoleContextKey, useLocale } from '../runtime/context';
import Session from './Session.vue';
import PasswordSetup from './PasswordSetup.vue';
import Problem from '../components/Problem.vue';
const props = defineProps<{
  intent: AuthenticationIntent;
  takeChallenge?: () => string | undefined;
  navigation: (
    locale: SupportedLocale,
  ) => readonly { path: string; label: string; icon: Component }[];
}>();
const { locale, setLocale } = useLocale();
const runtime = shallowRef<AuthenticationRuntime>();
const error = ref<string>();
const fatal = ref(false);
const route = useRoute();
const router = useRouter();
const bootstrap = createRuntimeConfigBootstrap();
const guards = new Map<symbol, () => boolean>();
let confirmation: Promise<boolean> | undefined;
function requestExit(): Promise<boolean> {
  if (![...guards.values()].some((guard) => guard())) return Promise.resolve(true);
  if (confirmation) return confirmation;
  confirmation = ElMessageBox.confirm(
    locale.value === 'zh-CN' ? '尚有未保存的修改，确定离开？' : 'Discard unsaved changes?',
    locale.value === 'zh-CN' ? '离开页面' : 'Leave page',
    {
      confirmButtonText: locale.value === 'zh-CN' ? '放弃修改' : 'Discard changes',
      cancelButtonText: locale.value === 'zh-CN' ? '继续编辑' : 'Keep editing',
      type: 'warning',
    },
  )
    .then(
      () => true,
      () => false,
    )
    .finally(() => {
      confirmation = undefined;
    });
  return confirmation;
}
provide(consoleContextKey, {
  get runtime() {
    if (!runtime.value) throw new Error('Runtime unavailable');
    return runtime.value;
  },
  locale,
  requestExit,
  guards,
});
const removeGuard = router.beforeEach(() =>
  runtime.value?.getState().status === 'authenticated' ? requestExit() : true,
);
const unload = (event: BeforeUnloadEvent) => {
  if ([...guards.values()].some((guard) => guard())) {
    event.preventDefault();
    event.returnValue = '';
  }
};
window.addEventListener('beforeunload', unload);
onScopeDispose(() => {
  removeGuard();
  window.removeEventListener('beforeunload', unload);
});
onErrorCaptured(() => {
  fatal.value = true;
  return false;
});
async function start(retry = false) {
  error.value = undefined;
  const result = retry ? await bootstrap.retry() : await bootstrap.start();
  if (result.status === 'failed') {
    error.value = result.error.code;
    return;
  }
  if (result.status !== 'ready') return;
  const created = createAuthenticationRuntimeAfterConfig(
    { ok: true, config: result.config },
    { realm: globalThis, intent: props.intent, fetch: (input, init) => fetch(input, init) },
  );
  if (created.ok) runtime.value = created.runtime;
  else error.value = created.error.code;
}
onMounted(() => start());
const items = computed(() => props.navigation(locale.value));
</script>
<template>
  <ElConfigProvider :locale="locale === 'zh-CN' ? zhCN : enUS">
    <div v-if="!runtime || fatal || route.path === '/password-setup'" class="console-actions">
      <ElSelect
        id="console-locale"
        :model-value="locale"
        aria-label="Language / 语言"
        style="width: 120px"
        @update:model-value="setLocale"
        ><ElOption
          v-for="item in supportedLocaleRegistry"
          :key="item.locale"
          :value="item.locale"
          :label="item.selfName"
      /></ElSelect>
    </div>
    <div v-if="fatal" class="console-auth">
      <Problem :title="locale === 'zh-CN' ? '当前页面无法显示' : 'Unable to display this page'" />
      <div class="console-actions">
        <ElButton @click="router.go(0)">{{ locale === 'zh-CN' ? '重新加载' : 'Reload' }}</ElButton>
      </div>
    </div>
    <div v-else-if="error" class="console-auth">
      <Problem :code="error" />
      <div class="console-actions">
        <ElButton @click="start(true)">{{ locale === 'zh-CN' ? '重试' : 'Retry' }}</ElButton>
      </div>
    </div>
    <div v-else-if="!runtime" class="console-auth" role="status">
      {{ locale === 'zh-CN' ? '正在加载' : 'Loading' }}
    </div>
    <div v-else-if="intent === 'TENANT' && route.path === '/password-setup'" class="console-auth">
      <PasswordSetup :take-challenge="takeChallenge" />
    </div>
    <Session v-else :navigation="items">
      <template #locale
        ><ElSelect
          id="console-locale"
          :model-value="locale"
          aria-label="Language / 语言"
          style="width: 120px"
          @update:model-value="setLocale"
          ><ElOption
            v-for="item in supportedLocaleRegistry"
            :key="item.locale"
            :value="item.locale"
            :label="item.selfName" /></ElSelect
      ></template>
    </Session>
  </ElConfigProvider>
</template>

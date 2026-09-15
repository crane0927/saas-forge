<script setup lang="ts">
import { onMounted, onScopeDispose, onErrorCaptured, provide, ref, shallowRef } from 'vue';
import { ElConfigProvider, ElMessageBox, ElButton } from 'element-plus';
import zhCN from 'element-plus/es/locale/lang/zh-cn';
import enUS from 'element-plus/es/locale/lang/en';
import { useRouter, useRoute } from 'vue-router';
import {
  createRuntimeConfigBootstrap,
  createAuthenticationRuntimeAfterConfig,
  type AuthenticationRuntime,
} from '@saas-forge/app-runtime';
import { applicationContextKey, type ApplicationContext } from './runtime/context';
import { consoleContextKey } from '@saas-forge/admin';
import { locale, $t } from './locales';
import { useThemeStore } from './store/modules/theme';
import Session from './store/Session.vue';
import LoginLayout from './views/_builtin/login/index.vue';
import Problem from './components/custom/Problem.vue';
useThemeStore();
const route = useRoute();
const router = useRouter();
const runtime = shallowRef<AuthenticationRuntime>();
const error = ref<string>();
const fatal = ref(false);
const bootstrap = createRuntimeConfigBootstrap();
const guards = new Map<symbol, () => boolean>();
let confirmation: Promise<boolean> | undefined;
let disposed = false;
function requestExit(): Promise<boolean> {
  if (![...guards.values()].some((guard) => guard())) return Promise.resolve(true);
  if (confirmation) return confirmation;
  confirmation = ElMessageBox.confirm($t('discardPrompt'), $t('leavePage'), {
    confirmButtonText: $t('discard'),
    cancelButtonText: $t('keepEditing'),
    type: 'warning',
  })
    .then(
      () => true,
      () => false,
    )
    .finally(() => {
      confirmation = undefined;
    });
  return confirmation;
}
// 迁移期仅向旧业务页提供同一 Runtime/退出守卫；正式认证入口归本应用所有。
const applicationContext: ApplicationContext = {
  get runtime() {
    if (!runtime.value) throw new Error('Runtime unavailable');
    return runtime.value;
  },
  locale,
  guards,
  requestExit,
};
provide(applicationContextKey, applicationContext);
provide(consoleContextKey, applicationContext);
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
  disposed = true;
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
  if (disposed) return;
  if (result.status === 'failed') {
    error.value = result.error.code;
    return;
  }
  if (result.status !== 'ready') return;
  const created = createAuthenticationRuntimeAfterConfig(
    { ok: true, config: result.config },
    { realm: globalThis, intent: 'PLATFORM', fetch: (input, init) => fetch(input, init) },
  );
  if (created.ok) runtime.value = created.runtime;
  else error.value = created.error.code;
}
onMounted(() => start());
</script>
<template>
  <ElConfigProvider :locale="locale === 'zh-CN' ? zhCN : enUS">
    <LoginLayout
      v-if="fatal || error || !runtime"
      :title="$t(fatal ? 'pageUnavailable' : 'loading')"
    >
      <Problem :code="error" :title="fatal ? $t('pageUnavailable') : undefined" />
      <ElButton v-if="fatal" @click="router.go(0)">{{ $t('reload') }}</ElButton>
      <ElButton v-else-if="error" @click="start(true)">{{ $t('retry') }}</ElButton>
      <p v-else role="status">{{ $t('loading') }}</p>
    </LoginLayout>
    <LoginLayout v-else-if="route.path === '/password-setup'" :title="$t('passwordSetupTitle')"
      ><Problem :title="$t('passwordSetupUnavailable')" /><ElButton
        @click="router.replace('/login')"
        >{{ $t('signIn') }}</ElButton
      ></LoginLayout
    >
    <Session v-else />
  </ElConfigProvider>
</template>

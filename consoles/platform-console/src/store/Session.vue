<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import { useAuthenticationRuntime } from './modules/auth';
import { useApplication } from '../runtime/context';
import type { AuthenticationOperationResult, AuthenticationProblem } from '@saas-forge/app-runtime';
import { $t } from '../locales';
import BaseLayout from '../layouts/base-layout/index.vue';
import LoginLayout from '../views/_builtin/login/index.vue';
import PwdLogin from '../views/_builtin/login/modules/pwd-login.vue';
import Problem from '../components/custom/Problem.vue';
const { runtime, requestExit } = useApplication();
const { state, businessReady } = useAuthenticationRuntime(runtime);
const router = useRouter();
const route = useRoute();
const recovered = ref(false);
const problem = shallowRef<AuthenticationProblem>();
const recoveryProblem = shallowRef<AuthenticationProblem>();
const busy = ref(false);
const changed = ref(false);
let pending: AbortController | undefined;
let returnPath: string | undefined;
const authPaths = new Set(['/login', '/change-password', '/recover']);
onMounted(async () => {
  pending = new AbortController();
  const result = await runtime.recover(pending.signal);
  if (pending.signal.aborted) return;
  if (
    !result.ok &&
    (result.problem.code === 'NETWORK_UNAVAILABLE' ||
      result.problem.status === 409 ||
      result.problem.status === 503)
  )
    recoveryProblem.value = result.problem;
  pending = undefined;
  recovered.value = true;
});
onScopeDispose(() => pending?.abort());
watch(
  () => [state.value, recovered.value, recoveryProblem.value, route.path],
  () => {
    if (!recovered.value || recoveryProblem.value || state.value.transition !== null) return;
    if (state.value.status === 'anonymous' && route.path !== '/login') {
      if (!authPaths.has(route.path) && route.matched.some((record) => record.meta.business))
        returnPath = route.fullPath;
      void router.replace('/login');
    } else if (state.value.status === 'passwordChangeRequired')
      void router.replace('/change-password');
    else if (state.value.status === 'authenticated' && authPaths.has(route.path))
      void router.replace(returnPath ?? '/');
  },
  { immediate: true },
);
async function act(operation: (signal: AbortSignal) => Promise<AuthenticationOperationResult>) {
  if (busy.value || pending) return;
  pending = new AbortController();
  const controller = pending;
  busy.value = true;
  problem.value = undefined;
  try {
    const result = await operation(controller.signal);
    if (controller.signal.aborted) return;
    if (!result.ok) problem.value = result.problem;
    return result;
  } finally {
    if (!controller.signal.aborted) {
      pending = undefined;
      busy.value = false;
    }
  }
}
async function submit(email: string, password: string) {
  if (state.value.status === 'anonymous')
    await act((signal) => runtime.login({ email, password, signal }));
  else if (state.value.status === 'passwordChangeRequired') {
    const result = await act((signal) =>
      runtime.changeInitialPassword({ newPassword: password, signal }),
    );
    if (result?.ok) changed.value = true;
  }
}
async function logout() {
  if (!(await requestExit())) return;
  returnPath = undefined;
  await act((signal) => runtime.logout(signal));
}
async function retryRecovery() {
  const result = await act((signal) => runtime.retryRecovery(signal));
  recoveryProblem.value = result && !result.ok ? result.problem : undefined;
}
const restoring = computed(() => !recovered.value || state.value.transition === 'recover');
const recovery = computed(
  () =>
    recoveryProblem.value ||
    (state.value.status === 'authenticated' &&
      (state.value.synchronizationProblem || state.value.transition === 'sessionSync')),
);
const title = computed(() =>
  $t(
    restoring.value
      ? 'recoveryRetryLoading'
      : recovery.value
        ? 'recoveryTitle'
        : state.value.status === 'passwordChangeRequired'
          ? 'initialPasswordChangeTitle'
          : state.value.status === 'logoutPending'
            ? 'logoutPendingTitle'
            : 'loginTitle',
    { applicationName: 'SaaS Forge' },
  ),
);
</script>
<template>
  <BaseLayout v-if="businessReady && !restoring && !recovery" :busy="busy" @logout="logout"
    ><Problem :code="problem?.code"
  /></BaseLayout>
  <LoginLayout v-else :title="title">
    <p v-if="restoring" role="status">{{ $t('recoveryRetryLoading') }}</p>
    <template v-else-if="recovery"
      ><Problem
        :code="
          recoveryProblem?.code ??
          (state.status === 'authenticated' ? state.synchronizationProblem?.code : undefined)
        "
      /><ElButton :loading="busy" @click="retryRecovery">{{
        $t('recoveryRetry')
      }}</ElButton></template
    >
    <template v-else>
      <Problem id="authentication-error" :code="problem?.code" />
      <p v-if="changed" role="status">{{ $t('passwordChanged') }}</p>
      <PwdLogin
        v-if="state.status === 'anonymous' || state.status === 'passwordChangeRequired'"
        :key="state.status"
        :change-password="state.status === 'passwordChangeRequired'"
        :busy="busy"
        :problem-code="problem?.code"
        @submit="submit"
      />
      <p v-if="state.status === 'logoutPending'">{{ $t('logoutPendingDescription') }}</p>
      <ElButton
        v-if="state.status === 'logoutPending' || problem?.code === 'SESSION_SLOT_ALREADY_ACTIVE'"
        :loading="busy"
        @click="logout"
        >{{ $t(state.status === 'logoutPending' ? 'retryLogout' : 'logout') }}</ElButton
      >
    </template>
  </LoginLayout>
</template>

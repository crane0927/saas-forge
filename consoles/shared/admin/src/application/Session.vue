<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter, RouterView } from 'vue-router';
import {
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElDialog,
  ElSelect,
  ElOption,
} from 'element-plus';
import type {
  AuthenticationOperationResult,
  AuthenticationProblem,
  IdempotentOperationHandle,
} from '@saas-forge/app-runtime';
import { useConsole, useShellText } from '../runtime/context';
import { useAuthenticationRuntime } from '../runtime/authentication';
import { useTenantMonitor } from '../runtime/tenant-monitor';
import { resolveBrandProfile, platformResolvedBrandProfile } from '../brand/resolved-brand';
import ConsoleLayout from '../layouts/ConsoleLayout.vue';
import type { Component as VueComponent } from 'vue';
import Problem from '../components/Problem.vue';
import PageHeading from '../components/PageHeading.vue';
const props = defineProps<{
  navigation: readonly { path: string; label: string; icon: VueComponent }[];
}>();
const { runtime, locale, requestExit } = useConsole();
const t = useShellText();
const route = useRoute();
const router = useRouter();
const { state, businessReady } = useAuthenticationRuntime(runtime);
const monitor = useTenantMonitor(runtime);
const recovered = ref(false);
const recoveryProblem = shallowRef<AuthenticationProblem>();
const problem = shallowRef<AuthenticationProblem>();
const busy = ref(false);
const email = ref('');
const password = ref('');
const changed = ref(false);
const switching = ref(false);
const target = ref('');
let handle: IdempotentOperationHandle | undefined;
let pending: AbortController | undefined;
let returnPath: string | undefined;
const authenticationPaths = new Set(['/login', '/change-password', '/select-context', '/recover']);
const schemeQuery = matchMedia('(prefers-color-scheme: dark)');
const dark = ref(schemeQuery.matches);
const schemeChanged = () => {
  dark.value = schemeQuery.matches;
};
schemeQuery.addEventListener('change', schemeChanged);
onScopeDispose(() => schemeQuery.removeEventListener('change', schemeChanged));
const brand = shallowRef(platformResolvedBrandProfile);
const brandReady = ref(true);
const context = computed(() =>
  state.value.status === 'authenticated' ? state.value.tenantContext : undefined,
);
watch(
  () => context.value,
  async (next, previous, cleanup) => {
    if (next === previous) return;
    const controller = new AbortController();
    cleanup(() => controller.abort());
    brandReady.value = false;
    const result = await resolveBrandProfile(next?.brandProfile, {
      signal: controller.signal,
      isCurrent: () => !controller.signal.aborted,
    });
    if (controller.signal.aborted) return;
    brand.value = result.resolvedBrand;
    brandReady.value = true;
  },
  { immediate: true },
);
watch(
  [brand, dark],
  ([value, isDark]) => {
    document.documentElement.classList.toggle('dark', isDark);
    document.documentElement.dataset.brand = value.source;
    document.documentElement.dataset.colorScheme = isDark ? 'dark' : 'light';
    const tokens = value.tokenSet[isDark ? 'dark' : 'light'];
    document.title =
      value.profile.displayName +
      (runtime.intent === 'PLATFORM'
        ? ' Platform Console'
        : value.source === 'tenant'
          ? ' · SaaS Forge Tenant Console'
          : ' Tenant Console');
    let icon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
    if (!icon) {
      icon = document.createElement('link');
      icon.rel = 'icon';
      document.head.append(icon);
    }
    icon.href = value.profile.faviconUrl;
    document.documentElement.style.setProperty('--el-color-primary', tokens.primary.color);
    document.documentElement.style.setProperty(
      '--console-primary-foreground',
      tokens.primary.foreground,
    );
    document.documentElement.style.setProperty('--console-accent', tokens.accent.color);
    document.documentElement.style.setProperty(
      '--console-accent-foreground',
      tokens.accent.foreground,
    );
  },
  { immediate: true },
);
const currentPath = computed(
  () =>
    props.navigation.find(
      (item) =>
        item.path !== '/' && (route.path === item.path || route.path.startsWith(item.path + '/')),
    )?.path ?? '/',
);
const pageTitle = computed(
  () => props.navigation.find((item) => item.path === currentPath.value)?.label ?? '',
);
function recoverable(value: AuthenticationProblem) {
  return value.code === 'NETWORK_UNAVAILABLE' || value.status === 409 || value.status === 503;
}
onMounted(async () => {
  pending = new AbortController();
  const result = await runtime.recover(pending.signal);
  if (pending.signal.aborted) return;
  if (!result.ok && recoverable(result.problem)) recoveryProblem.value = result.problem;
  pending = undefined;
  recovered.value = true;
});
onScopeDispose(() => {
  pending?.abort();
  password.value = '';
  handle = undefined;
});
watch(
  () => [state.value, recovered.value, recoveryProblem.value, route.path],
  () => {
    if (!recovered.value || recoveryProblem.value || state.value.transition !== null) return;
    if (state.value.status === 'anonymous' && route.path !== '/login') {
      if (
        !authenticationPaths.has(route.path) &&
        router.resolve(route.fullPath).matched.some((r) => r.meta.business)
      )
        returnPath = route.fullPath;
      void router.replace('/login');
    } else if (state.value.status === 'passwordChangeRequired')
      void router.replace('/change-password');
    else if (state.value.status === 'contextSelectionRequired')
      void router.replace('/select-context');
    else if (state.value.status === 'authenticated' && authenticationPaths.has(route.path))
      void router.replace(returnPath ?? '/');
  },
  { immediate: true },
);
async function act(operation: (signal: AbortSignal) => Promise<AuthenticationOperationResult>) {
  if (busy.value || pending) return;
  pending = new AbortController();
  busy.value = true;
  problem.value = undefined;
  const result = await operation(pending.signal);
  if (pending.signal.aborted) return;
  pending = undefined;
  busy.value = false;
  if (!result.ok) problem.value = result.problem;
  return result;
}
async function login() {
  const secret = password.value;
  password.value = '';
  await act((signal) => runtime.login({ email: email.value, password: secret, signal }));
}
async function changePassword() {
  const secret = password.value;
  password.value = '';
  const result = await act((signal) =>
    runtime.changeInitialPassword({ newPassword: secret, signal }),
  );
  if (result?.ok) changed.value = true;
}
async function retryRecovery() {
  const result = await act((signal) => runtime.retryRecovery(signal));
  recoveryProblem.value = result && !result.ok ? result.problem : undefined;
}
async function logout() {
  if (!(await requestExit())) return;
  returnPath = undefined;
  await act((signal) => runtime.logout(signal));
}
async function retryTenantSwitch() {
  const result = await act((signal) => runtime.retryTenantSwitchRefresh(signal));
  if (result?.ok) {
    switching.value = false;
    handle = undefined;
    target.value = '';
  }
}
async function switchTenant() {
  if (!target.value || busy.value || !(await requestExit())) return;
  busy.value = true;
  problem.value = undefined;
  const result = await runtime.switchTenantContext({
    membershipId: target.value,
    ...(handle ? { operationHandle: handle } : {}),
  });
  busy.value = false;
  if (result.ok) {
    switching.value = false;
    handle = undefined;
    target.value = '';
  } else {
    problem.value = result.problem;
    handle = result.operationHandle;
  }
}
</script>
<template>
  <div
    v-if="!recovered || state.transition === 'recover' || !brandReady"
    class="console-auth"
    role="status"
  >
    {{ t('recoveryRetryLoading') }}
  </div>
  <div
    v-else-if="
      recoveryProblem || (state.status === 'authenticated' && state.transition === 'sessionSync')
    "
    class="console-auth"
  >
    <Problem
      :title="t('recoveryTitle')"
      :code="
        recoveryProblem?.code ||
        (state.status === 'authenticated' ? state.synchronizationProblem?.code : undefined)
      "
    />
    <div class="console-actions">
      <ElButton :loading="busy" @click="retryRecovery">{{ t('recoveryRetry') }}</ElButton>
    </div>
  </div>
  <div
    v-else-if="state.status === 'authenticated' && state.transition === 'tenantSwitchRefresh'"
    class="console-auth"
  >
    <h1>{{ t('tenantSwitchCommittedTitle') }}</h1>
    <Problem :code="problem?.code" />
    <div class="console-actions">
      <ElButton :loading="busy" @click="retryTenantSwitch">{{
        t('tenantSwitchRefreshRetry')
      }}</ElButton>
    </div>
  </div>
  <div
    v-else-if="monitor.unavailable.value && state.status === 'authenticated'"
    class="console-auth"
  >
    <Problem :title="t('tenantMonitorUnavailable')" />
    <div class="console-actions">
      <ElButton @click="router.go(0)">{{ t('tenantMonitorReload') }}</ElButton>
    </div>
  </div>
  <ConsoleLayout
    v-else-if="businessReady"
    :application-name="brand.profile.displayName"
    :logo-url="brand.profile.logoUrl"
    :navigation-label="t('globalNavigation', { applicationName: brand.profile.displayName })"
    :skip-label="locale === 'zh-CN' ? '跳转到主要内容' : 'Skip to main content'"
    :collapse-label="locale === 'zh-CN' ? '收起导航' : 'Collapse navigation'"
    :expand-label="locale === 'zh-CN' ? '展开导航' : 'Expand navigation'"
    :current-path="currentPath"
    :page-title="pageTitle"
    :items="navigation"
    @navigate="router.push($event)"
  >
    <template #actions
      ><slot name="locale" /><ElButton
        v-if="context && context.accessibleMemberships.length > 1"
        @click="switching = true"
        >{{ t('tenantSwitch') }}</ElButton
      ><ElButton :loading="busy" @click="logout">{{ t('logout') }}</ElButton></template
    >
    <RouterView v-slot="{ Component }"
      ><component
        :is="Component"
        :key="
          context?.membershipId +
          ':' +
          String(route.matched[0]?.name) +
          ':' +
          (route.matched.length > 1 ? '' : String(route.params.id ?? ''))
        "
    /></RouterView>
    <ElDialog
      v-model="switching"
      :title="t('tenantSwitchTitle')"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy && !handle"
      :show-close="!busy && !handle"
      width="min(560px, 95vw)"
    >
      <Problem :code="problem?.code" />
      <ElSelect v-model="target" :aria-label="t('tenantSwitchTitle')" :disabled="busy || !!handle"
        ><ElOption
          v-for="item in context?.accessibleMemberships.filter(
            (item) => item.membershipId !== context?.membershipId,
          )"
          :key="item.membershipId"
          :value="item.membershipId"
          :label="item.tenantDisplayName"
      /></ElSelect>
      <template #footer
        ><ElButton :disabled="busy || !!handle" @click="switching = false">{{
          t('cancel')
        }}</ElButton
        ><ElButton type="primary" :loading="busy" :disabled="!target" @click="switchTenant">{{
          t('tenantSwitch')
        }}</ElButton></template
      >
    </ElDialog>
  </ConsoleLayout>
  <div v-else class="console-auth">
    <ElCard shadow="never">
      <div class="console-actions"><slot name="locale" /></div>
      <PageHeading
        :key="state.status"
        visible
        :title="
          state.status === 'anonymous'
            ? t('loginTitle', { applicationName: brand.profile.displayName })
            : state.status === 'passwordChangeRequired'
              ? t('initialPasswordChangeTitle')
              : state.status === 'logoutPending'
                ? t('logoutPendingTitle')
                : t('contextSelectionTitle')
        "
      />
      <Problem :code="problem?.code" />
      <p v-if="changed" role="status">{{ t('passwordChanged') }}</p>
      <Problem v-if="monitor.ended.value" :title="t('tenantSessionEndedTitle')" />
      <ElForm v-if="state.status === 'anonymous'" label-position="top" @submit.prevent="login">
        <ElFormItem :label="t('emailLabel')"
          ><ElInput
            v-model="email"
            type="email"
            autocomplete="username"
            :aria-label="t('emailLabel')"
            required
            :disabled="busy"
        /></ElFormItem>
        <ElFormItem :label="t('passwordLabel')"
          ><ElInput
            v-model="password"
            type="password"
            autocomplete="current-password"
            :aria-label="t('passwordLabel')"
            required
            :disabled="busy"
        /></ElFormItem>
        <div class="console-actions">
          <ElButton v-if="problem?.code === 'SESSION_SLOT_ALREADY_ACTIVE'" @click="logout">{{
            t('logout')
          }}</ElButton
          ><ElButton type="primary" native-type="submit" :loading="busy">{{
            t('signIn')
          }}</ElButton>
        </div>
      </ElForm>
      <ElForm
        v-else-if="state.status === 'passwordChangeRequired'"
        label-position="top"
        @submit.prevent="changePassword"
      >
        <ElFormItem :label="t('newPasswordLabel')"
          ><ElInput
            v-model="password"
            type="password"
            autocomplete="new-password"
            :aria-label="t('newPasswordLabel')"
            required
            :disabled="busy"
        /></ElFormItem>
        <div class="console-actions">
          <ElButton type="primary" native-type="submit" :loading="busy">{{
            t('passwordUpdate')
          }}</ElButton>
        </div>
      </ElForm>
      <div v-else-if="state.status === 'contextSelectionRequired'" class="console-actions">
        <ElButton
          v-for="item in state.memberships"
          :key="item.membershipId"
          :loading="busy"
          @click="
            act(() => runtime.selectAuthenticationContext({ membershipId: item.membershipId }))
          "
          >{{ t('contextSelectionEnter', { tenantName: item.tenantDisplayName }) }}</ElButton
        >
      </div>
      <div v-else-if="state.status === 'logoutPending'" class="console-actions">
        <ElButton :loading="busy" @click="logout">{{ t('retryLogout') }}</ElButton>
      </div>
    </ElCard>
  </div>
</template>

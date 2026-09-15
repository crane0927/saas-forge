<script setup lang="ts">
import { provide, ref } from 'vue';
import { ElConfigProvider } from 'element-plus';
import zhCN from 'element-plus/es/locale/lang/zh-cn';
import enUS from 'element-plus/es/locale/lang/en';
import { createAuthenticationRuntimeAfterConfig } from '../shared/app-runtime/src';
import { consoleContextKey } from '../shared/admin/src/runtime/context';
import Session from '../shared/admin/src/application/Session.vue';
import type { SupportedLocale } from '../shared/i18n/src';
const props = defineProps<{ locale: SupportedLocale; failure?: boolean }>();
const result = createAuthenticationRuntimeAfterConfig(
  { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
  {
    realm: {},
    intent: 'PLATFORM',
    fetch: async () =>
      new Response(
        JSON.stringify({
          code: props.failure ? 'UPSTREAM_UNAVAILABLE' : 'SESSION_NOT_FOUND',
          status: props.failure ? 503 : 401,
          title: 'Unavailable',
          type: props.failure
            ? 'urn:saas.forge:problem:upstream-unavailable'
            : 'urn:saas.forge:problem:session-not-found',
          detail: 'Isolated fixture',
          traceId: '0123456789abcdef0123456789abcdef',
        }),
        {
          status: props.failure ? 503 : 401,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      ),
  },
);
if (!result.ok) throw new Error('Invalid fixture');
provide(consoleContextKey, {
  runtime: result.runtime,
  locale: ref(props.locale),
  requestExit: async () => true,
  guards: new Map(),
});
</script>
<template>
  <ElConfigProvider :locale="locale === 'zh-CN' ? zhCN : enUS"
    ><Session :navigation="[]"
  /></ElConfigProvider>
</template>

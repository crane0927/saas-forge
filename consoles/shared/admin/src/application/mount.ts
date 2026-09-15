import { createApp, type Component } from 'vue';
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { createPinia } from 'pinia';
import { ElLoading } from 'element-plus';
import type { AuthenticationIntent } from '@saas-forge/app-runtime';
import type { SupportedLocale } from '@saas-forge/i18n';
import ConsoleApplication from './ConsoleApplication.vue';

export function mountConsole(options: {
  intent: AuthenticationIntent;
  routes: RouteRecordRaw[];
  navigation: (
    locale: SupportedLocale,
  ) => readonly { path: string; label: string; icon: Component }[];
}) {
  let challenge: string | undefined;
  if (options.intent === 'TENANT' && location.pathname === '/password-setup') {
    const candidate = new URLSearchParams(location.hash.slice(1)).get('token');
    if (candidate && /^[A-Za-z0-9_-]{43}$/.test(candidate)) challenge = candidate;
    history.replaceState(history.state, '', '/password-setup');
  }
  const empty = { render: () => null };
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      ...options.routes,
      ...['login', 'change-password', 'select-context', 'recover', 'password-setup'].map(
        (path) => ({ path: '/' + path, component: empty }),
      ),
      { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
  });
  createApp(ConsoleApplication, {
    intent: options.intent,
    navigation: options.navigation,
    takeChallenge: () => {
      const value = challenge;
      challenge = undefined;
      return value;
    },
  })
    .use(createPinia())
    .use(router)
    .directive('loading', ElLoading.directive)
    .mount('#app');
}

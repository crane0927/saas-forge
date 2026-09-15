import { HomeFilled } from '@element-plus/icons-vue';
import { createTranslator } from '@saas-forge/i18n';
import { mountConsole } from '@saas-forge/admin';
import '@saas-forge/admin/styles.css';
import { tenantMessages } from './messages';
if (
  (import.meta.env.DEV || import.meta.env.MODE === 'static-acceptance') &&
  location.pathname === '/acceptance/static-remote'
) {
  const [{ createApp }, { default: StaticRemoteAcceptance }] = await Promise.all([
    import('vue'),
    import('./StaticRemoteAcceptance.vue'),
  ]);
  createApp(StaticRemoteAcceptance).mount('#app');
} else {
  mountConsole({
    intent: 'TENANT',
    navigation: (locale) => [
      {
        path: '/',
        label: createTranslator({
          namespace: '@saas-forge/tenant-console-shell',
          locale,
          messages: tenantMessages,
        }).translate('navigationWorkspace'),
        icon: HomeFilled,
      },
    ],
    routes: [
      {
        path: '/',
        name: 'workspace',
        component: () => import('./Workspace.vue'),
        meta: { business: true },
      },
    ],
  });
}

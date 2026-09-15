import { OfficeBuilding, HomeFilled, Key, Collection, SetUp } from '@element-plus/icons-vue';
import { createTranslator } from '@saas-forge/i18n';
import { mountConsole } from '@saas-forge/admin';
import '@saas-forge/admin/styles.css';
import { platformMessages } from './messages';
import ResourceList from './ResourceList.vue';
mountConsole({
  intent: 'PLATFORM',
  navigation: (locale) => {
    const translator = createTranslator({
      namespace: '@saas-forge/platform-console',
      locale,
      messages: platformMessages,
    });
    const t = translator.translate.bind(translator);
    return [
      { path: '/', label: t('navigationHome'), icon: HomeFilled },
      { path: '/tenants', label: t('tenantsTitle'), icon: OfficeBuilding },
      { path: '/quota-definitions', label: t('quotaDefinitionsTitle'), icon: SetUp },
      { path: '/plans', label: t('planDefinitionsTitle'), icon: Collection },
      { path: '/oauth-clients', label: 'OAuth Client', icon: Key },
    ];
  },
  routes: [
    {
      path: '/',
      name: 'overview',
      component: () => import('./Overview.vue'),
      meta: { business: true },
    },
    {
      path: '/tenants',
      name: 'tenants',
      component: ResourceList,
      props: { kind: 'tenant' },
      meta: { business: true },
      children: [
        {
          path: 'new',
          name: 'tenant-create',
          component: () => import('./TenantCreate.vue'),
          meta: { drawer: true },
        },
      ],
    },
    {
      path: '/tenants/:id',
      name: 'tenant-detail',
      component: () => import('./TenantDetails.vue'),
      meta: { business: true },
    },
    {
      path: '/plans',
      name: 'plans',
      component: ResourceList,
      props: { kind: 'plan' },
      meta: { business: true },
      children: [
        {
          path: 'new',
          name: 'plan-create',
          component: () => import('./PlanCreate.vue'),
          meta: { drawer: true },
        },
        {
          path: ':id',
          name: 'plan-detail',
          component: () => import('./EntitlementDetails.vue'),
          props: { kind: 'plan' },
          meta: { drawer: true },
        },
      ],
    },
    {
      path: '/quota-definitions',
      name: 'quotas',
      component: ResourceList,
      props: { kind: 'quota' },
      meta: { business: true },
      children: [
        {
          path: 'new',
          name: 'quota-create',
          component: () => import('./QuotaCreate.vue'),
          meta: { drawer: true },
        },
        {
          path: ':id',
          name: 'quota-detail',
          component: () => import('./EntitlementDetails.vue'),
          props: { kind: 'quota' },
          meta: { drawer: true },
        },
      ],
    },
    {
      path: '/oauth-clients/operations',
      name: 'oauth-operations',
      component: () => import('./OAuthOperations.vue'),
      meta: { business: true },
    },
    {
      path: '/oauth-clients',
      name: 'oauth',
      component: ResourceList,
      props: { kind: 'oauth' },
      meta: { business: true },
      children: [
        {
          path: 'new',
          name: 'oauth-create',
          component: () => import('./OAuthCreate.vue'),
          meta: { drawer: true },
        },
        {
          path: ':id',
          name: 'oauth-detail',
          component: () => import('./OAuthDetails.vue'),
          meta: { drawer: true },
        },
      ],
    },
  ],
});

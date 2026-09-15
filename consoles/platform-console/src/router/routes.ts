import type { RouteRecordRaw } from 'vue-router';
import ResourceList from '../ResourceList.vue';
export const businessRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'overview',
    component: () => import('../views/home/index.vue'),
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
        component: () => import('../TenantCreate.vue'),
        meta: { drawer: true },
      },
    ],
  },
  {
    path: '/tenants/:id',
    name: 'tenant-detail',
    component: () => import('../TenantDetails.vue'),
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
        component: () => import('../PlanCreate.vue'),
        meta: { drawer: true },
      },
      {
        path: ':id',
        name: 'plan-detail',
        component: () => import('../EntitlementDetails.vue'),
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
        component: () => import('../QuotaCreate.vue'),
        meta: { drawer: true },
      },
      {
        path: ':id',
        name: 'quota-detail',
        component: () => import('../EntitlementDetails.vue'),
        props: { kind: 'quota' },
        meta: { drawer: true },
      },
    ],
  },
  {
    path: '/oauth-clients/operations',
    name: 'oauth-operations',
    component: () => import('../OAuthOperations.vue'),
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
        component: () => import('../OAuthCreate.vue'),
        meta: { drawer: true },
      },
      {
        path: ':id',
        name: 'oauth-detail',
        component: () => import('../OAuthDetails.vue'),
        meta: { drawer: true },
      },
    ],
  },
];

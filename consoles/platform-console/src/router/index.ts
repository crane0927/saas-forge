import { createRouter, createWebHistory } from 'vue-router';
import { businessRoutes } from './routes';
// 密码设置契约只属于 TENANT；平台不得保存或转发误入的 challenge。
if (location.pathname === '/password-setup')
  history.replaceState(history.state, '', '/password-setup');
const empty = { render: () => null };
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...businessRoutes,
    ...['login', 'change-password', 'recover', 'password-setup'].map((path) => ({
      path: '/' + path,
      component: empty,
    })),
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});

import { createApp, type App, nextTick } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { afterEach, expect, it } from 'vitest';
import { page } from 'vitest/browser';
import axe from 'axe-core';
import Layout from '../shared/admin/test/LayoutFixture.vue';
import Authentication from './AuthenticationFixture.vue';
import '../shared/admin/src/styles.css';
let app: App;
let root: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  root?.remove();
  document.documentElement.classList.remove('dark');
});
async function audit() {
  const result = await axe.run(root, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21aa'] },
  });
  expect(result.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }))).toEqual(
    [],
  );
}
for (const scheme of ['light', 'dark'] as const)
  for (const width of [1440, 1024])
    it(`Soybean layout ${scheme} ${width}`, async () => {
      await page.viewport(width, 900);
      document.documentElement.lang = 'zh-CN';
      document.documentElement.classList.toggle('dark', scheme === 'dark');
      root = document.createElement('div');
      root.id = 'app';
      document.body.append(root);
      app = createApp(Layout);
      app.mount(root);
      await nextTick();
      await expect.element(page.getByText('布局测试数据', { exact: true })).toBeVisible();
      await audit();
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(innerWidth);
      if (import.meta.env.SF_VISUAL_SNAPSHOTS === 'true')
        await expect.element(root).toMatchScreenshot(`soybean-layout-${scheme}-${width}`);
    });
for (const locale of ['zh-CN', 'en-US'] as const)
  for (const failure of [false, true])
    it(`authentication ${locale} ${failure ? 'recovery' : 'login'}`, async () => {
      await page.viewport(1280, 900);
      document.documentElement.lang = locale;
      root = document.createElement('div');
      root.id = 'app';
      document.body.append(root);
      const router = createRouter({
        history: createMemoryHistory(),
        routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
      });
      app = createApp(Authentication, { locale, failure }).use(router);
      app.mount(root);
      await expect
        .element(
          page.getByRole('button', {
            name: failure
              ? locale === 'zh-CN'
                ? '重试恢复'
                : 'Retry recovery'
              : locale === 'zh-CN'
                ? '登录'
                : 'Sign in',
            exact: true,
          }),
        )
        .toBeVisible();
      await audit();
      if (import.meta.env.SF_VISUAL_SNAPSHOTS === 'true')
        await expect
          .element(root)
          .toMatchScreenshot(`authentication-${locale}-${failure ? 'recovery' : 'login'}`);
    });

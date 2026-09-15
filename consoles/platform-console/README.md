# Platform Console

独立的 Vite + Vue 3 平台管理应用。按 Issue #191 基于固定版本 Soybean Admin Element Plus 的登录、后台布局、导航、标签栏和首页结构接入 SaaS Forge；来源、许可证及允许差异见 [UPSTREAM.md](src/vendor/soybean/UPSTREAM.md)。

## 启动

首次在仓库根目录准备依赖与类型化 API Client：

```bash
pnpm --dir consoles install --frozen-lockfile
pnpm --dir consoles run generate:api
```

然后在本目录执行 `pnpm run dev`。浏览器使用 `https://platform.saas.forge.test`，后端和 HTTPS Gateway 由开发者管理；Vite 的 HTTP 监听地址仅供内部转发。生产部署须原子替换 `public/runtime-config.json` 的非法模板值。

## 应用边界

- `main.ts` / `App.vue` 安装 Vue I18n、Pinia、Router，并在配置校验成功后创建本 Realm 唯一的 PLATFORM Runtime。登录、刷新恢复、首次改密和退出均通过该 Runtime。
- `/login`、`/change-password` 使用官方登录结构，`/` 展示真实会话和业务快捷入口；原 Tenant、Quota Definition、Plan、OAuth Client 路由和抽屉保持可用。
- 后台顶栏沿用 Soybean 的图标工具区：菜单搜索、全屏切换、语言选择、明暗主题切换和主题配置均保留；搜索只针对当前平台路由，不创建额外 API 请求。
- 密码设置链接属于 TENANT 契约。平台 `/password-setup` 会清除 URL 敏感片段并显示不可用，不保存、不消费、不转发 challenge；租户端单次消费流程保持原样。
- 新应用使用模板 Vue I18n 机制，ICU 消息通过固定版本自定义编译器处理；不支持的 Locale 安全回退到英文。只持久化非敏感语言与主题偏好。
- 未迁移业务页面暂用 `@saas-forge/admin` / `@saas-forge/i18n`；`App.vue` 将同一 Runtime 和离页守卫传给这些页面。旧认证应用不挂载，无产品切换开关。后续迁移按父 Issue #190 删除剩余消费者依赖。

## 验证

```bash
pnpm run typecheck
pnpm run lint
pnpm run format:check
pnpm run test
pnpm run build
```

`platform-soybean.test.mjs` 使用正式路由与模拟 HTTP，覆盖明暗主题、语言、登录、刷新、退出、首次改密、失败焦点与策略提示、WCAG 自动检查。`console-vue-products.test.mjs` 保留旧业务路由的恢复、权限、抽屉和脏表单回归。

模拟 HTTP、类型检查和构建不代表真实验收通过。Issue #191 仍需由当前工作区经受信 HTTPS 在 Chrome 完成真实登录到首页及退出，并保留脱敏证据；固定版本外观对照与真实验收状态分别记录。

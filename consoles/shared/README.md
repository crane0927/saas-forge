# SaaS Forge Shared Frontend

[English](README-en.md)

Platform Console 与 Tenant Console Shell 共用的前端基础能力。这里按职责拆分为四个私有 workspace 包：生成式 API Client、无 UI 的应用 Runtime、React 应用壳和公共 Design System。

环境安装、应用启动与部署配置见 [Console 工作区指南](../README.md)。`shared/` 不是独立的 pnpm workspace，也不是另一个可部署控制台。

## 包与职责

| 包                                                     | 负责                                                                           | 不负责                                            |
| ------------------------------------------------------ | ------------------------------------------------------------------------------ | ------------------------------------------------- |
| [`@saas-forge/api-client`](api-client/)                | 从 OpenAPI 生成 API、模型和请求运行时类型，通过稳定根入口导出                  | 认证状态、CSRF 策略、Token 存储、页面与导航       |
| [`@saas-forge/app-runtime`](app-runtime/README.md)     | Runtime Config、Bootstrap、认证状态机、会话协调和受控类型化 API 调用           | React、路由、样式和视觉组件                       |
| [`@saas-forge/react-shell`](react-shell/)              | 认证页面、Membership 选择与切换交互、受保护路由、导航、恢复/重试界面及错误边界 | 另建认证状态、直接调用认证 HTTP、实现产品业务页面 |
| [`@saas-forge/design-system`](design-system/README.md) | 统一 Provider、主题、语义 Token、布局、表单、表格及无障碍交互                  | 认证状态、业务授权、产品路由和领域规则            |

> [!NOTE]
> 认证、刷新、退出、Tenant Context 切换与多标签页会话协调已由 Runtime 和 React Shell 承担。业务 Remote 的加载协议、Manifest 与 Module Federation 不在当前实现中；现有 [Remote 夹具](../business-remotes/design-system-consumer-fixture/README.md) 只验证 Design System 消费边界。

## 依赖方向

以下仅展示共享包之间及其 UI 实现的直接依赖，箭头表示“依赖”：

```text
react-shell ──> app-runtime ──> api-client
     │
     └────────> design-system ──> antd
```

`app-runtime` 不依赖 React、React Shell 或 Design System；`design-system` 不依赖认证 Runtime。React 与路由适配留在上层，API 生成细节留在底层。所有共享包都只公开包根入口，不提供内部路径作为消费契约。

## 使用边界

### 宿主接线

1. Console 从同 Origin 的 `/runtime-config.json` 加载并校验配置。配置只包含 `schemaVersion` 与 `apiBaseUrl`，API 地址必须是绝对 HTTPS Origin；失败时保持关闭并提供显式重试。
2. 配置成功后，宿主通过 `createAuthenticationRuntimeAfterConfig` 创建固定 `PLATFORM` 或 `TENANT` 意图的 Runtime。同一页面 Realm 的同一意图复用同一个实例。
3. 将该 Runtime 交给 `AuthenticationShell`，由宿主提供本地路由和应用身份；页面与 Remote 不再创建认证 Runtime。
4. 每个 Console 入口只安装一个 `DesignSystemProvider`。Tenant 品牌经受控公共能力应用，不新增第二层 Provider。

实际接线参考 [Platform Console](../platform-console/src/app.tsx) 与 [Tenant Console Shell](../tenant-console-shell/src/app.tsx)，Provider 入口见对应的 [Platform main](../platform-console/src/main.tsx) / [Tenant main](../tenant-console-shell/src/main.tsx)。

### HTTP 与凭据

- 页面和 Remote 通过宿主 Runtime 的受控类型化 Client 调用正式 API operation，不直接实例化生成 Client 来绕过认证边界。
- Access Token 由 Runtime 管理且不持久化；消费者不能读取 Token、取得通用凭据型 `fetch`、覆盖 API Origin，或自行注入 Cookie、Origin、Fetch Metadata、Bearer Token。
- 浏览器管理 HttpOnly Cookie、Origin 与 `Sec-Fetch-*`；Runtime 承担认证请求、刷新、CSRF 请求标记和失败归一化，生成式 Client 本身不拥有这些策略。

当前 `runtime.client` 公开 `getOAuthClient` 与 `createOAuthClient`，不是任意 URL 的 HTTP 代理。存在类型化 operation 不代表对应产品页面已完成；接口以 [`app-runtime` 公共入口](app-runtime/src/index.ts) 为准。

### 公共 UI

只从 `@saas-forge/design-system` 根入口消费组件。Console 与 Remote 不得直接依赖 `antd`、导入内部文件、注入全局 CSS、覆盖 `.ant-*` / `.sf-*` 内部选择器或复制已有公共组件。领域内容可用 CSS Modules 排列；共享组件缺少能力时，先在 Design System 中补充最小公共 API，再由消费者使用。

### 生成代码

[`contracts/openapi/`](../../contracts/openapi/) 是 API 契约来源，Maven/OpenAPI Generator 是唯一生成权威。输出目录 `api-client/.generated/` 被 Git 忽略，不应手改或直接导入；手写代码经 `@saas-forge/api-client` 根入口引用生成 API 与类型。

## 开发与验证

先按 [工作区指南](../README.md#快速开始) 准备固定版本的 Node、pnpm、JDK 和浏览器。以下命令均从 `consoles/` 执行，不在 `shared/` 中单独安装依赖。

先生成正式 API Client，再按修改范围执行包级检查：

```bash
pnpm run generate:api
pnpm --filter @saas-forge/api-client run typecheck
pnpm --filter @saas-forge/app-runtime run verify
pnpm --filter @saas-forge/react-shell run verify
pnpm --filter @saas-forge/design-system run verify
```

`api-client` 只有类型检查命令；Runtime 与 React Shell 的 `verify` 包含类型、单元测试、Lint 和格式检查；Design System 额外执行浏览器测试与构建。包级命令不反向调用 Maven。

共享改动还需验证消费者；已有生成 Client 时可运行：

```bash
pnpm run verify:workspace
```

该门禁覆盖严格类型、Lint、格式、工作区边界、包测试、Chromium 浏览器测试和生产制品检查。从未生成 Client 的状态开始时，直接运行 `pnpm run verify`。Chrome、Edge、Firefox、WebKit 兼容命令见 [工作区验证说明](../README.md#常用命令与验证)。

> [!IMPORTANT]
> 包级测试通过不等于两个 Console 和 Remote 夹具均已通过集成验证，工作区门禁也不替代真实后端与受信 TLS 的产品验收。不要将生成 Client 或构建成功作为业务功能完成的依据。

## 进一步阅读

- [Console 工作区指南](../README.md)
- [Runtime Config 与认证内核](app-runtime/README.md)
- [Design System 组件与消费规则](design-system/README.md)
- [Console Authentication Runtime 设计](../../docs/28-console-authentication-runtime.md)
- [真实 Console 认证验收](../../docs/acceptance/issue-115-console-authentication.md)

# SaaS Forge Consoles

[English](README-en.md)

SaaS Forge 的前端工作区：两个独立部署的 React 控制台，共用认证 Runtime、React 应用壳、Design System 和生成式 API Client。

- **Platform Console**：面向 SaaS 产品提供方的平台管理入口，固定使用 `PLATFORM` 认证意图。
- **Tenant Console Shell**：面向租户管理员的应用宿主，固定使用 `TENANT` 认证意图，支持 Membership 选择、Tenant Context 切换与受控品牌展示。
- **共享基础能力**：严格运行配置、会话恢复、登录、首次改密、退出、多标签页会话协调，以及统一的组件与交互规则。

> [!NOTE]
> 当前主要交付控制台宿主与认证能力。Platform 的 `/` 为总览页，`/oauth-clients` 仍是占位入口；Tenant 的 `/` 为工作台。产品业务 Remote、Manifest 与 Module Federation 尚未接入，不能将现有路由视为完整业务管理功能。

## 快速开始

### 环境要求

| 工具                | 要求                          | 用途                             |
| ------------------- | ----------------------------- | -------------------------------- |
| Node.js             | `24.14.1`                     | 前端开发与验证                   |
| pnpm                | `11.22.0`，通过 Corepack 启用 | 唯一工作区包管理器               |
| JDK                 | `17`；仓库 CI 同时验证 `21`   | Maven 生成 TypeScript API Client |
| Playwright Chromium | 安装后运行浏览器门禁          | `verify` 的必需依赖              |

本目录是唯一的 pnpm workspace 根。依赖声明使用 [默认 Catalog](pnpm-workspace.yaml)，解析版本由 `pnpm-lock.yaml` 锁定；后端构建使用仓库自带的 Maven Wrapper，无需另装 Maven。

从仓库根目录准备依赖并完成前端验证：

```bash
cd consoles
corepack enable
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm run verify
```

Linux CI 使用 `pnpm exec playwright install --with-deps chromium` 准备浏览器系统依赖。首次安装及 API Client 生成需要能够访问相应的依赖仓库。

### 启动开发服务器

以下命令均在 `consoles/` 执行；同时开发两个应用时，分别使用一个终端：

```bash
pnpm run dev:platform
```

```bash
pnpm run dev:tenant
```

两个命令都会先生成 API Client，再启动对应的 Vite 服务器，访问地址以终端输出为准。仅查看共享组件时，可启动 Design System 展示册：

```bash
pnpm --filter @saas-forge/design-system run dev:showcase
```

> [!IMPORTANT]
> 开发服务器只提供前端，不启动 Gateway、IAM 或数据库。它通过 `/runtime-config.json` 提供固定的 `https://api.saasforge.test` API Origin。真实认证联调还需要受信 HTTPS、正确的域名解析、Gateway 安全配置与已准备的账户；默认 HTTP localhost 页面不能代替受控浏览器入口。环境准备见 [Compose 部署说明](../deploy/compose/README.md)。

## 目录与职责

| 目录                                                                                                            | 职责                                                                    |
| --------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [`platform-console/`](platform-console/)                                                                        | 独立 Vite + React 平台应用，拥有本地路由和固定认证意图                  |
| [`tenant-console-shell/`](tenant-console-shell/)                                                                | 独立租户应用宿主，连接 Tenant Context、导航和品牌展示                   |
| [`shared/api-client/`](shared/api-client/)                                                                      | 无状态 TypeScript REST Client，只公开稳定包入口                         |
| [`shared/app-runtime/`](shared/app-runtime/README.md)                                                           | 不依赖 React/路由的运行配置、Bootstrap、认证状态机和受控类型化 API 调用 |
| [`shared/react-shell/`](shared/react-shell/)                                                                    | 共享认证页面、受保护路由、导航、恢复/重试界面及分层错误边界             |
| [`shared/design-system/`](shared/design-system/README.md)                                                       | 唯一公共 UI 包：主题、语义 Token、布局、表单、表格与交互规则            |
| [`business-remotes/design-system-consumer-fixture/`](business-remotes/design-system-consumer-fixture/README.md) | 仅用于验证共享 UI 消费边界的 Remote 夹具，不是产品 Remote               |
| `test/`、`browser-test/`、`integration-test/`                                                                   | 工作区边界、浏览器消费与会话/产品集成测试                               |

### 开发边界

- **API 生成**：Maven/OpenAPI Generator 是唯一生成权威，输入来自 [`contracts/openapi/`](../contracts/openapi/)，输出到被 Git 忽略的 `shared/api-client/.generated/`。不要手改生成物或直接导入生成目录；使用 `@saas-forge/api-client` 公开入口。
- **认证与 HTTP**：页面和 Remote 复用宿主 Runtime，通过其受控类型化 Client 调用正式 API operation；不得创建第二套认证状态、读取 Token 或自行注入 Cookie、Origin、Fetch Metadata、Bearer Token。Access Token 不持久化；生成式 Client 本身不负责会话、CSRF 或 Token 存储。
- **共享 UI**：每个 Console 入口只安装一个 `DesignSystemProvider`。消费者只从 `@saas-forge/design-system` 根入口导入，不直接依赖 `antd`、导入内部路径、注入全局 CSS、覆盖公共组件内部选择器或复制已有公共组件。领域内容布局可使用 CSS Modules。
- **配置失败关闭**：先校验 Runtime Config，再进入认证与应用路由。配置加载失败只暴露安全错误码并允许显式重试，不回退到猜测的 API 地址。

## 常用命令与验证

以下命令均在 `consoles/` 执行。

| 命令                                      | 范围                                                                 |
| ----------------------------------------- | -------------------------------------------------------------------- |
| `pnpm run generate:api`                   | 通过 Maven 正式生成 API Client                                       |
| `pnpm run typecheck`                      | 递归执行严格 TypeScript 检查，包含生成 Client                        |
| `pnpm run lint` / `pnpm run format:check` | 手写代码与文档的 ESLint / Prettier 检查；生成物不参与                |
| `pnpm run test`                           | 工作区静态边界检查与各包测试，不包含根浏览器套件                     |
| `pnpm run test:browser:chromium`          | Design System、消费者及多标签页会话的 Chromium 测试                  |
| `pnpm run test:browser:compatibility`     | 依次运行 Chrome、Edge、Firefox、WebKit 兼容测试                      |
| `pnpm run build`                          | 生成 Client、递归生产构建并检查 Design System 制品边界               |
| `pnpm run verify`                         | 生成 Client，再执行完整前端聚合门禁                                  |
| `pnpm run verify:workspace`               | 不生成 Client，直接执行同一个前端聚合门禁，供 Maven 等已生成流程复用 |

聚合门禁顺序为：类型检查 → ESLint → Prettier → 边界与包测试 → Chromium 浏览器测试 → 生产构建与制品检查。`typecheck`、`test` 和浏览器命令不会生成 Client，单独运行前需先执行 `pnpm run generate:api`。

兼容测试需先准备相应浏览器；也可用 `test:browser:chrome`、`test:browser:edge`、`test:browser:firefox` 或 `test:browser:webkit` 单独运行：

```bash
pnpm exec playwright install chrome msedge firefox webkit
pnpm run generate:api
pnpm run test:browser:compatibility
```

两个应用的包级 `dev`、`typecheck`、`lint`、`format:check`、`test`、`build`、`verify` 只处理本包，不反向调用 Maven，也不替代工作区门禁。仓库根目录的 `./mvnw verify` 会先生成 Client，再调用 `verify:workspace`；Maven 不负责安装 Node、pnpm、前端依赖或浏览器。

### 验证范围

工作区门禁覆盖共享包边界、UI 交互、会话协调和静态制品一致性，不等同于真实后端登录或部署验收。WebKit 是可复现的 Safari 引擎兼容测试，不代表原生 Safari 实测。

真实 Console 认证使用独立的 [`verify-console-authentication-e2e.sh`](../scripts/verify-console-authentication-e2e.sh)，涉及全新 Compose 环境、受信 TLS 与真实服务请求，不属于 `pnpm run verify`。执行前请阅读 [产品验收说明与环境前提](../docs/acceptance/issue-115-console-authentication.md)；该文档中的历史结果不代表当前环境已验证通过。

## 构建与部署

运行 `pnpm run build` 后，分别发布两个独立静态制品：

- `platform-console/dist/` → Platform Console Origin。
- `tenant-console-shell/dist/` → Tenant Console Origin。

每个制品内的 `/runtime-config.json` 都是故意非法的模板。部署流程必须原子替换为严格的两字段配置，例如：

```json
{
  "schemaVersion": 1,
  "apiBaseUrl": "https://api.example.test"
}
```

`apiBaseUrl` 必须是无凭据、业务路径、查询参数和 Fragment 的绝对 HTTPS Origin。配置不得携带密钥，也不能改变应用身份、路由、菜单或授权行为。Vite 的开发配置不会注入生产 Bundle。

> [!WARNING]
> 每次重新构建都会重新带入 `REPLACE_DURING_DEPLOYMENT` 模板，必须再次替换配置。漏配时应用会停在配置错误页，这是预期的失败关闭行为。

静态站点还需为客户端路由提供 SPA 回退，并正确返回 `/runtime-config.json`，不能将其误回退为 HTML。两个前端 Origin 与 API Origin 的 TLS、CORS、Cookie 和 Gateway 配置必须匹配；具体拓扑见 [Compose 部署说明](../deploy/compose/README.md)。

## 常见问题

| 现象                                  | 排查方向                                                                                                                |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `ERR_PNPM_VERIFY_DEPS_BEFORE_RUN`     | 核对 Node/pnpm 版本及 lockfile，回到 `consoles/` 执行冻结安装；不要关闭 `verifyDepsBeforeRun: error` 或改用其他包管理器 |
| 找不到生成 Client 或 API 类型         | 在工作区根目录运行 `pnpm run generate:api`，并检查 JDK/Maven 依赖访问                                                   |
| Playwright 提示浏览器可执行文件不存在 | 安装所运行测试对应的引擎或 Chrome/Edge 渠道                                                                             |
| 页面停在配置错误状态                  | 检查 `/runtime-config.json` 的 HTTP 响应、JSON 两字段契约和 HTTPS Origin；生产环境需替换模板                            |
| 页面可打开，但认证请求失败            | 核对真实 API 可达性、受信证书、入口域名与 Gateway 安全边界；页面可见不证明认证链路可用                                  |

## 进一步阅读

- [仓库概览](../README.md)
- [Console Authentication Runtime 设计](../docs/28-console-authentication-runtime.md)
- [Design System 公共组件与消费规则](shared/design-system/README.md)
- [Compose 环境与浏览器访问准备](../deploy/compose/README.md)
- [Console 认证产品验收记录](../docs/acceptance/issue-115-console-authentication.md)

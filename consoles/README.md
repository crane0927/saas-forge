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

### 受控 HTTPS Console 开发入口

在 macOS Docker Desktop 上，从仓库根目录执行一次性准备，再显式选择 Console：

```bash
bash scripts/local-development.sh setup
pnpm --dir consoles run build:static-remote
bash scripts/local-development.sh doctor
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh status
bash scripts/local-development.sh frontend stop all
```

`setup` 复用有效的本地 CA；服务器证书缺少受控 Host、将在 24 小时内失效或无法通过链/私钥校验时才重签 leaf。证书覆盖 `platform.saasforge.test`、`console.saasforge.test`、`api.saasforge.test`、`remote.saasforge.test`。旧双/三 Host 安装需重新执行 setup；hosts 和 Keychain 变更仍分别要求交互式明确授权，已配置时幂等跳过，非交互环境拒绝系统变更。

`frontend` 必须提供 `start|status|stop` 和 `platform|tenant|all`。Platform Vite 固定监听 `127.0.0.1:5173`，Tenant 固定监听 `127.0.0.1:5174`，均启用 strict port，分别只接受对应受控 Host，HMR 使用对应 HTTPS Origin 的 WSS 443。Edge 通过 `host.docker.internal` 访问两个回环 Vite，将 API 转发到当前 Gateway，并保留浏览器安全头。未知 Host 被拒绝；不得为解决 Docker Desktop 连通性问题将 Vite 改为所有网络接口。

启动使用 Node `24.14.1`、pnpm `11.22.0` 和既有依赖，复用健康兼容的 Edge。日常启停不生成证书、不修改 hosts/信任、不安装依赖、不生成 API Client，也不启动后端或重置账户。已有不兼容 Edge 占用 443 时会阻止启动；升级旧 Edge 前先停止两个 Console，再重新启动。

`start all` 在启动前捕获两个 Console 与 Edge 状态并预检；两个正式 HTTPS Host 都就绪后才成功。已有健康进程与兼容 Edge 会被复用，重复启动不重启资源；任一步骤失败只回收本次调用新启动的进程与 Edge。`stop all` 先核验两个目标，再停止受管 Vite 和当前项目 Edge；未知 PID、端口归属或 Edge 配置错误会阻塞变更。

`frontend status all` 只读显示两个 Console 的固定端口、HTTPS 就绪结果和共享 Edge；顶层 `status` 先输出这些信息，再执行原有五个后端服务检查。正常 `STOPPED`、可识别的 `STALE` 与短暂 `STARTING` 不使前端聚合失败；`UNREADY`、`UNMANAGED`、Edge `INVALID` 或 `UNAVAILABLE` 返回非零。状态输出不包含凭据或原始环境变量。

两个 Console 各自使用 Git 忽略目录 `deploy/compose/.secrets/local-https-development/` 内的 `platform-vite.pid|log` 和 `tenant-vite.pid|log`，PID 与追加日志权限均为 0600。`status` 报告 `RUNNING`、`STOPPED`、`STARTING`、`STALE`、`UNMANAGED` 或 `UNREADY`；RUNNING 要求 PID、进程组、启动时间、仓库、包身份、回环监听和正式 HTTPS 就绪全部匹配。停止只向身份匹配的目标发送 SIGTERM；另一个 Console 活动或身份不明时保留 Edge，最后一个停止后仅停止 Edge 容器，不删除容器、后端服务或卷。旧 `vite.pid` 只由 Platform 在身份匹配时认领，旧日志保留；陈旧记录仅在确认原进程不存在后清理。

包级 `pnpm --filter @saas-forge/tenant-console-shell run dev` 仍可前台调试；占用 5174 时，统一生命周期报告 UNMANAGED 并拒绝终止它。前台 HTTP 调试不能替代受控 HTTPS 验收。

Tenant Origin 下的 `/password-setup`、`/password-setup/app.js`、`/password-setup/styles.css` 和 `/api/v1/auth/password-setups` 精确转发到当前活动 Gateway，查询参数与浏览器请求头原样保留；页面和资源的内容类型、缓存头及 API 错误响应由 Gateway 决定。其他 Tenant 路径和 HMR 继续进入 Tenant Vite。

这些路径与 API Host 共用活动目标文件；`bash scripts/local-development.sh replace gateway` 后跟随本地 Gateway，`restore gateway` 后回到容器，无需修改浏览器 URL 或重启 Edge。目标文件缺失、非法或目标不可达时返回 502，不回退到 Vite 或其他 Gateway；未知 Host 返回 421。首次升级路由时，按前述步骤停止两个 Console 后重新启动，以加载新的 Edge 脚本。

账户、Gateway 与后端仍需另行准备。上述路由能力不等同于完整 Tenant 认证验收。

#### 第四域静态资源验收

开发 Tenant 的 `https://console.saasforge.test/acceptance/static-remote` 从 Remote 加载构建后的两个版本，不加入产品导航。执行 `pnpm --dir consoles run verify:local:static-remote`，在正常受信 Chromium 中验证模块执行、CSS、图片、无凭据 CORS 和 Vite WSS 连接；脱敏证据写入 `.scratch/issue-156/`。准备、旧 Edge 受控升级、版本冻结及 E2E 复用边界见[第四域开发验收说明](../docs/local-static-remote-development.md)。这不是 Manifest、业务 Remote 或父规格 #155 的整体验收。

#### 状态与恢复

旧的无参数 `bash scripts/local-development.sh frontend` 已移除，会返回用法错误。以下九种命令是完整替代入口，均从仓库根目录执行：

```bash
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend status platform
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh frontend stop all
```

| 状态        | 含义                                          | 恢复动作                                                                                                                             |
| ----------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `RUNNING`   | 受管身份、回环监听与正式 HTTPS 就绪均通过     | 正常开发；重复 start 会复用                                                                                                          |
| `STOPPED`   | 没有受管记录，也没有对应端口监听              | 需要开发时显式 start                                                                                                                 |
| `STARTING`  | 受管进程已创建，30 秒启动窗口内尚无监听       | 等待后再次 status；不要并行反复启动                                                                                                  |
| `STALE`     | 有记录，但原进程已不存在，端口也未被占用      | 执行对应目标 stop 安全清理，再 start                                                                                                 |
| `UNMANAGED` | 记录身份不可信，或端口属于未知进程            | 用 `lsof -nP -iTCP:5173 -iTCP:5174 -iTCP:443 -sTCP:LISTEN` 核对；由原启动者结束其前台命令，再 status。不要按端口杀进程或直接删除 PID |
| `UNREADY`   | 受管进程存在，但超时未监听或正式 HTTPS 未就绪 | 检查对应日志并运行 doctor；排除 Edge/证书/依赖问题后，显式 stop 再 start                                                             |

日志分别位于 `deploy/compose/.secrets/local-https-development/platform-vite.log` 与 `tenant-vite.log`；本地查看即可，不复制可能含敏感信息的原始日志到报告。Edge `UNAVAILABLE` 应先检查 Docker Desktop 与 Docker 访问权限；`INVALID`/`UNMANAGED` 需先核对项目归属和配置，不能自动替换未知监听者。`doctor` 的修复提示不授权本次验收修改证书信任或后端。

直接包级前台调试可在 `consoles/` 的两个终端分别执行：

```bash
pnpm --filter @saas-forge/platform-console run dev
pnpm --filter @saas-forge/tenant-console-shell run dev
```

包级命令不生成 API Client；工作区 `dev:platform`/`dev:tenant` 则先生成再启动。两者均不属于受管 PID 生命周期，应由原终端 Ctrl-C 结束；HTTP localhost 显示页面只能说明前端可渲染，不能作为登录、Cookie、CSRF 或 TLS 安全验收证据。

#### 双 Console 产品路径验收与恢复

1. 验收前保存 `frontend status all`、顶层 `status`，以及当前项目 Edge 的容器身份、运行状态和后端容器启动时间。确认已有受信证书、hosts、依赖和后端就绪；本轮不运行 setup、bootstrap、replace/restore 后端或任何密码重置。
2. 依次覆盖 Platform-only、Tenant-only、all、单目标停止、all 停止与重复操作，每步读取聚合状态。单目标停止须保留仍被另一 Console 使用的 Edge；前端 stop 不停止后端、不删除容器、Secret 或数据卷，也不终止未知监听者。
3. 在同一浏览器上下文中，以正常证书校验打开 `https://platform.saasforge.test` 和 `https://console.saasforge.test`。检查页面身份、关键内容、错误覆盖层、console/network，并分别记录真实 `/api/*` 方法、脱敏路径与状态码；API Origin 为 `https://api.saasforge.test`。禁止记录密码、Cookie、Token 或敏感响应体。
4. 使用既有账号或会话分别登录/恢复两个槽位；刷新一侧后另一侧仍可使用，登出一侧后另一侧刷新仍保持登录，再交换方向验证。缺少现有登录前提就记录阻塞，不创建账号或重置凭据。
5. 从 Tenant Origin 打开 Password Setup 文档，检查脚本/样式及表单的真实提交是否到达当前 Gateway。只验证不改变密码的失败路径；缺少可安全提交的前提则记录阻塞，不消费有效 Challenge。错误响应仅证明路由，不代表密码设置成功。
6. 在两个 Console 各临时修改一个可见开发标记，分别观察其受控 WSS Origin 的连接与 HMR 更新，再还原文件。用宿主监听检查证明 5173/5174 仅绑定 `127.0.0.1`，从 Edge 内经 `host.docker.internal` 访问二者，并检查宿主 LAN 地址直连两端口失败。Docker Desktop 无法访问回环 Vite 时停止验收，不回退到 `0.0.0.0`。
7. 无论成功或失败，都还原开发标记并恢复最初的受管前端组合；若验收前仅 Edge 运行，前端 stop 会连带停止它，应核对原容器身份后仅启动该 Edge 容器（`docker start <已核对的原 Edge 容器 ID>`）。未知或异常状态不能通过强杀恢复。最后只读复核前端/Edge 状态和后端身份、启动时间，记录无法恢复的差异。

验收报告逐项区分通过、失败和缺少前提，不把脚本测试或以前的 Platform 证据写成 Tenant 实机证据，也不改写 #126/#131 的历史范围。

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

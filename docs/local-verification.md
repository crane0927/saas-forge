# 按影响范围进行本地验证

普通修改先选择受影响模块及必要集成边界，再执行检查。以下流程复用 Maven、pnpm 和现有脚本，不推断 Git diff，也不自动启动完整环境。依据 [ADR 0044](adr/0044-local-feedback-and-complete-acceptance-use-separate-scopes.md)；实际记录见 [Issue #167 验收](acceptance/issue-167-local-verification.md)。

## 前端日常流程

先准备固定工具链 Node 24.14.1、pnpm 11.22.0。首次安装在 `consoles` 执行 `pnpm install --frozen-lockfile`；正式 API Client 缺失或输入变化时，在仓库根执行 `pnpm --dir consoles run generate:api`。准备与检查分开记录，普通前端修改无需启动后端或 Compose。

仅 Platform Console 自身实现变化的代表性流程：

```bash
# 检查已生成 Client；失败时按提示准备，不使用过期 Client。
node consoles/scripts/check-api-client.mjs
bash scripts/verify-frontend-workspace.sh --package @saas-forge/platform-console
```

入口执行该包现有 `verify`：类型检查、lint、格式检查、单元测试、构建串行完成。任一步失败即停止并返回非零；后续步骤是未执行，不是通过。Tenant Shell 可选择 `@saas-forge/tenant-console-shell`。精确包名拼错、通配符、额外参数或包没有 `verify` 都必须失败。Design System 的包级 `verify` 本身包含浏览器测试，不应移除。

循环开发时可先执行包内单文件测试，再执行包级 `verify`：

```bash
pnpm --dir consoles --filter @saas-forge/platform-console run typecheck
# 用该包内实际测试路径替换 <test-file>，不要写 --if-present 掩盖缺失脚本。
pnpm --dir consoles --filter @saas-forge/platform-console exec vitest run <test-file>
```

共享模块变化必须覆盖消费者。用 `pnpm --dir consoles --filter '...@saas-forge/react-shell' list --depth -1` 查看自身和传递消费者，然后执行：

```bash
pnpm --dir consoles --workspace-concurrency=1 --filter '...@saas-forge/react-shell' run verify
```

`...` 前缀选消费者，后缀选依赖，不可混淆。包级入口不自动扩大范围。`api-client` 没有 `verify`，其契约或生成逻辑变化走下述完整前端与跨服务契约验证，不把仅有的 typecheck 当作完整验证。样式、交互与可访问性变化增加对应真实浏览器检查；共享布局、Design System 和认证消费者变化按下表升级。

## 后端日常流程

使用仓库 Maven Wrapper，JDK 17 或 21，先确保 Maven 依赖可用。代表性服务发现模块的流程：

```bash
# 开发循环：单文件测试；上游聚合模块没有该测试时允许无匹配，务必核对目标测试实际运行。
./mvnw --batch-mode --no-transfer-progress -Pbackend-local \
  -pl services/service-discovery -am -Dtest=DiscoveredGrpcChannelTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 收尾：移除单文件过滤，运行该模块及其 Reactor 依赖的单元/集成检查。
./mvnw --batch-mode --no-transfer-progress -Pbackend-local \
  -pl services/service-discovery -am verify
```

其他服务用真实路径替换 `-pl` 参数，例如 `services/audit-service`。`-am` 只补上游依赖，不补消费者；共享模块变更需把消费者列入 `-pl`，必要时用完整 Reactor。涉及 PostgreSQL、Redis、Kafka 的测试需要相应 Testcontainers 运行条件；不要以 `-DskipTests`、`-DskipITs` 或测试被跳过来宣称验证通过。

`backend-local` 仅使 OpenAPI 模块的前端聚合执行跳过（Maven 输出 `skipping execute as per configuration`），保留代码生成和后端测试。不加该 profile 的 `./mvnw verify` 仍是原完整门禁，CI/发布流程不使用本地 profile。单独选择的后端模块若本来就不依赖 OpenAPI，调整前也不会运行前端；本选项解决包含 OpenAPI 的聚合调用，不代表所有后端命令都会加速。

## 按边界升级

| 改动边界 | 必须补充的验证 |
| --- | --- |
| 认证、Cookie、CORS、CSRF、Browser Session Slot | Gateway、IAM、受影响服务测试与真实 HTTPS 浏览器认证/会话场景；`pnpm --dir consoles run verify:local:session-security` 及对应 Issue 的安全矩阵，不能由包级单测代替 |
| OpenAPI、Protobuf、事件或跨服务调用 | 生产者与消费者的单元、契约、集成测试；`./mvnw verify` 的兼容性/工程门禁；前端消费者执行 `pnpm --dir consoles run verify` |
| Flyway | 遵守迁移不可变规则；`java script/FlywayMigrationGenerator.java validate` 与对应数据库迁移/隔离集成测试 |
| Nacos | 对应 revision 递增；`bash scripts/validate-nacos-config.sh` 与相关服务验证；权限、发布或恢复变更补相应专项入口 |
| Design System 国际化资源新增或移动 | 更新资源校验入口；`pnpm --dir consoles run validate:i18n` 与 `pnpm --dir consoles run build:workspace`，保留 tree-shake 检查 |
| 共享 UI/布局、浏览器行为 | 消费者包级验证与对应浏览器测试；兼容性变化升级至 Chrome、Edge、Firefox、WebKit |
| 具体 Issue / PRD 明确要求的本地、fresh 或端到端验收 | 原要求继续有效，不因本地分层流程而豁免 |

## 完整复现与 CI 覆盖清单

逐项串行运行，先按各入口文档准备环境。以下命令分别覆盖不同范围，不能互相代称“全通过”：

```bash
./mvnw --batch-mode --no-transfer-progress verify
pnpm --dir consoles run verify
pnpm --dir consoles run test:browser:compatibility
bash scripts/verify-tenant-lifecycle-e2e.sh
bash scripts/verify-console-authentication-e2e.sh
```

- Maven 完整 Reactor：JDK 17/21、后端单元/集成/契约、数据库/Redis/Kafka、JaCoCo 聚合质量门；OpenAPI 阶段调用前端 `verify:workspace`，含 Chromium。
- 独立前端 `verify`：正式 Client 生成后执行全工作区校验、测试、Chromium 和构建；与 Maven 有重复，供前端独立复现，日常无需两者都跑。
- `.github/workflows/verify.yml`：JDK 21、独立 Chrome/Edge/Firefox/WebKit、Tenant fresh-volume、Nacos 配置/权限/恢复及 CLI 接缝回归；调用认证 reusable workflow 提供 JDK 17 与五渠道 Fresh 产品证据。
- `.github/workflows/console-authentication-e2e.yml`：同一 job 先执行完整 JDK 17 Maven/workspace，再用 `--product` 复用制品执行五渠道 Fresh 产品与四渠道兼容门禁；失败直接传播。保留手动触发和本机默认完整入口，覆盖映射、准备条件与证据边界见 [Issue #168](acceptance/issue-168-ci-verification.md)。Nacos 的发布、ACL、恢复入口与环境配置以 Verify workflow 为准。
- 五服务本机替换矩阵保留 `bash scripts/verify-local-development-matrix.sh` 及其既有准备要求，作为专项复现，不是普通修改的默认门禁。

## 结果和实测记录

每条命令记录选择理由、覆盖边界、退出码、测试通过/失败/跳过数及未执行项目：

| 状态 | 判定 |
| --- | --- |
| PASS / 通过 | 命令退出 0，且预期检查确实执行；核对测试数量与报告 |
| FAIL / 失败 | 退出非零，包括缺依赖、环境阻断、无匹配包或测试失败；保留原日志 |
| SKIP / 跳过 | runner 明确报告跳过，或显式 profile 跳过前端；记录原因，不计入通过 |
| NOT_RUN / 未执行 | 不在所选范围，或前序失败使后续未启动；记录原因与升级入口 |

本地 CLI 接缝回归：

```bash
node --test --test-concurrency=1 \
  scripts/test/scoped-frontend-verification.test.mjs \
  scripts/test/backend-verification-profile.test.mjs \
  scripts/test/console-acceptance-prerequisites.test.mjs
```

使用真实 pnpm 隔离工作区和真实 Maven 执行，验证选择范围、默认全量入口、不兼容工具链和子检查失败传播。测试不以读取命令字符串代替运行结果。

macOS 的单命令记录示例（在仓库根执行）：

```bash
mkdir -p .scratch/verification
if /usr/bin/time -l bash scripts/verify-frontend-workspace.sh \
    --package @saas-forge/platform-console \
    > .scratch/verification/platform.log 2>&1; then
  echo 'PASS: Platform 包级检查；其他范围 NOT_RUN'
else
  result=$?
  echo "FAIL: exit=$result；查看 .scratch/verification/platform.log"
  exit "$result"
fi
```

Linux 使用 `/usr/bin/time -v`。重定向保留完整输出；使用 `tee` 时必须开启 `set -o pipefail`，避免日志命令掩盖失败。分别记录依赖/生成准备、构建、测试与整条流程，不把它们混算；为拆分阶段额外执行的命令不应累加到原单次完整流程。

调整前后使用相同机器、工具链、缓存条件和代表性范围；覆盖范围不同单独说明，禁止直接宣称加速比。记录单命令 RSS 高水位的操作系统口径（不是所有子进程同时占用之和，也不含 Docker VM）、swap、后台依赖和命令行响应探针；用户对 IDE/输入流畅度的观察单独记录，探针不能证明 UI 流畅。不要无依据并行启动重型检查。

5 分钟是日常反馈目标，不是硬截止或减免必要验证的理由。首次下载、冷编译、数据库/浏览器准备和边界扩展可能超时；记录瓶颈并保留必要检查，优先保证电脑能继续开发。

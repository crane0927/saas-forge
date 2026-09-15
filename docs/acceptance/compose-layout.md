# Compose 运行环境与服务拆分验证

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

日期：2026-09-14。设计依据：[ADR 0049](../adr/0049-compose-separates-environment-and-service-lifecycles.md)。

## 已完成

- `deploy/compose` 仅保留基础设施、初始化和共享 HTTPS 入口。
- Gateway、四个业务服务、两个 Console 及验收接收端分别拥有 Compose 项目与变量模板；应用连接共享 external 网络。
- 服务迁移与按需维护任务归所属服务，迁移成功仍是容器应用启动前提。
- 共用 Dockerfile、静态托管脚本归 `deploy/docker`；完整验收组合和五个场景覆盖归 `deploy/acceptance`，通过 `extends` 复用服务定义。
- 同步调整验收脚本、JWT 初始化、旧完整环境替换工具、CI 与中英文使用文档；路由目录生成器改为检查服务所属模块的 Compose 登记。

## 通过

| 验证 | 结果 |
| --- | --- |
| `python3 scripts/validate-compose-layout.py` | 8 个独立应用配置、环境归属、不同项目名、共享 external 网络、迁移门禁、维护 profiles、挂载及构建路径通过；5 个验收场景分别以两个项目名验证网络和数据卷隔离 |
| 拆分前工作区配置与拆分后验收组合比较 | 默认组合与 5 个场景的 `docker compose config --format json` 在归一化计划搬迁的文件路径后完全一致；使用合成环境变量，未读取真实凭据 |
| `bash scripts/validate-local-compose-jwt.sh` | IAM JWT 环境、只读私钥挂载及 IAM 目录变量模板通过；使用合成变量 |
| 既有 HTTPS、Remote、Password Setup、Edge 生命周期及本机替换回归 | 55 项通过；首次沙箱执行中 18 项因本地监听 `EPERM` 失败，获准在可监听本地端口的环境重跑后全部通过 |
| `node --test scripts/test/compose-initialization.test.mjs` | 2 项通过；替身 Docker 验证日常模式分别访问环境/IAM 项目，完整验收模式只操作指定验收项目，在数据库读取处终止，不生成密钥 |
| `mvn --offline --batch-mode --no-transfer-progress -pl saas-forge-contracts/saas-forge-http-route-catalog -am test` | 路由目录生成、构建及 4 项契约测试通过 |
| 修改的 Shell 脚本语法及 `git diff --check` | 通过 |

配置布局检查已加入 `verify.yml`；初始化项目路由测试已加入 `console-authentication-e2e.yml`。

## 未执行

- 未构建或启动真实应用容器，未执行完整 Fresh Compose、真实浏览器联调或远端 CI。
- 未停止、替换或迁移现有用户管理的容器、IDE 服务、数据卷及实际凭据。
- 首次切换独立项目仍需开发者停止旧项目应用、分配各目录实际环境变量并核对共享网络；默认环境保留原 `compose` 项目及卷名。参见[运行环境说明](../../deploy/compose/README.md)。

以上配置和自动化检查不等于完整运行验收通过。

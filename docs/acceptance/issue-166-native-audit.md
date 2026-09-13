# Issue #166：Audit 原生启动验收

## 实现范围

- 增加 Audit 个人 local 模板，支持开发者指定数据库、Kafka、Nacos 和自身注册地址；local 不请求 Nacos 配置中心，非 local 保持受控 Nacos 导入。
- 保留 Nacos 注册确认、V5 迁移可见性、Kafka 连通性与双 Consumer 分区就绪检查；不改消费组、事实映射、提交/确认顺序、去重和追加权限。
- 个人配置沿用 Git ignore，并与模板一起排除在发布 JAR 外；迁移仍独立执行，应用不取得迁移权限。
- [开发说明](../native-audit-development.md) 给出 IDE Run/Debug/重启、凭据注入、正式来源操作与按事件 ID 观测的步骤。

## 自动化证据（2026-09-10）

- `LocalConfigurationTest` 先因缺失模板失败，补齐后通过，验证真实 Spring Config Data 加载、可指定依赖地址、关闭配置中心、保留注册和运行就绪分组。日志：`/tmp/issue-166-red.log`、`/tmp/issue-166-green.log`。
- 聚焦就绪测试首次在沙箱内受 Mockito JVM attach 限制失败，获准在沙箱外运行模块全套后通过。初次失败记录：`/tmp/issue-166-focused.log`。
- `mvn -pl services/audit-service -am verify` 退出码 0，耗时 53.749 秒。54 项单元测试、15 项 PostgreSQL/Kafka 集成测试，失败、错误、跳过均为 0。日志：`/tmp/issue-166-verify.log`。
- 现有 `SessionStartedConsumerPostgreSqlKafkaIT` 通过真实 Kafka/PostgreSQL 验证提交后未确认时的重投去重、数据库事务失败不确认、Tenant 事实映射及运行角色追加权限。事件由测试构造，不作为真实来源服务操作的证据。
- `git check-ignore` 确认个人 YAML 被忽略；发布 JAR 列表不包含 local 配置或模板；`git diff --check` 通过。
- 初次双轴审查：Standards 0 项；Spec 1 项 P1 验收缺口，为当时尚未执行的真实来源消费和 IDE 重启恢复；下述现场证据已补齐此项。未发现明确的实现错误或范围扩张。

## 现场验收

2026-09-10，开发者已停止 `compose-audit-service-1`，随后从 IDEA 启动 Audit。个人配置由模板复制，使用仅含三个 Audit 运行凭据的受限 configtree（目录 700、文件 600），复用已有身份，没有修改 ACL 或管理员身份。

北京时间 17:31 检查结果：

- 原生 Java 进程 PID `96803` 监听 `127.0.0.1:8084`，readiness 返回 200/`UP`。
- 使用 Audit 工作负载身份读取真实 Nacos，健康注册为 `127.0.0.1:8084`。
- `audit-service.iam-session-events` 和 `audit-service.tenant-events` 各有一个 Consumer，分别取得一个分区。
- 使用应用账号只读核验迁移 V1–V5 成功；`audit_app` 对 `audit_records` 的 SELECT/INSERT 权限为 true，UPDATE/DELETE/TRUNCATE 为 false。
- 正式登录前最新 IAM Outbox 事件为 `01a08a2b-1980-7b5c-bb48-e9ec8a38baf0`，发生于 `2026-09-10T07:14:35Z`，已发布。

### 正式登录事件消费

开发者通过现有 Platform Console 完成正式登录，IAM Outbox 产生 `com.saasforge.iam.session.started.v1`：

| 观测项 | 结果（UTC） |
| --- | --- |
| event_id | `01a08aaa-9064-7798-b777-6ff586163277` |
| 来源发生时间 | `2026-09-10T09:33:48Z` |
| Outbox published_at | `2026-09-10T09:33:50.714663Z` |
| Audit recorded_at / consumed_at | `2026-09-10T09:33:50.814703Z` |
| Audit Record | 一条，source=`urn:saasforge:iam-service`，action=`SESSION_STARTED`，result=`SUCCESS` |
| 资源 | `REFRESH_TOKEN_FAMILY` / `01a08aaa-9047-7d52-ad20-803b29038df2` |
| 去重记录 | 一条，consumer_name=`audit-service.iam-session-events` |

使用各服务应用账号的只读事务按同一 `event_id` 查询来源 Outbox、Audit Record 与去重记录；没有手工插入审计数据。查询时原生 Audit readiness 仍为 `UP`。

### 停机积压

开发者在 IDEA 停止 Audit；`2026-09-10T09:38:49Z` 核验 8084 无监听进程，两个消费组均无活跃成员。随后通过 Platform Console 退出并重新登录：

- 登录事件 `01a08aaf-b4cc-7488-86ed-f8137b080caf`，发生于 `2026-09-10T09:39:26Z`，Outbox 于 `2026-09-10T09:39:26.632922Z` 发布。
- Audit 仍停止时，按该事件 ID 查询 Audit Record 和去重记录，均为 0。
- IAM 消费组仍无成员，分区 0 的 committed offset 为 255、log-end offset 为 257、lag 为 2；两条新事实分别为退出产生的 session revoked 和再次登录产生的 session started。

### IDE 重启恢复

开发者以原配置在 IDEA 再次启动 Audit。新 Java 进程 PID `98127` 监听 `127.0.0.1:8084`，readiness 返回 200/`UP`。

- IAM 消费组仍为 `audit-service.iam-session-events`，committed offset 从 255 前进到 257，与 log-end offset 257 一致，lag 从 2 降为 0。
- 停机期间的登录事件 `01a08aaf-b4cc-7488-86ed-f8137b080caf` 于 `2026-09-10T09:41:35.149101Z` 保存为一条 Audit Record，action=`SESSION_STARTED`，resource_type=`REFRESH_TOKEN_FAMILY`，resource_id=`01a08aaf-b4c4-7cfb-9217-ba4f6b0c223a`，result=`SUCCESS`；对应去重记录一条。
- 首次登录事件 `01a08aaa-9064-7798-b777-6ff586163277` 的 Audit Record 和去重记录仍各一条，原 recorded_at 未变。
- 全程保留现有 Kafka、数据库和开发者数据；没有重置 offset、变更消费组或手工插入审计记录。应用未取得迁移权限，来源 IAM 及其他应用继续沿用当前运行方式。

最终双轴复审：Standards 0 项，Spec 0 项；此前唯一 P1 现场验收缺口已解决。现场阶段仅补充验收文档及 Git 忽略的个人配置，没有修改产品代码，因此未重复运行此前已通过的 54 项单元测试和 15 项集成测试；文档 `git diff --check` 通过。

Issue #166 要求的配置、聚焦集成测试、真实来源事件消费及 IDE 停止/重启恢复均已完成。完整仓库 CI、Fresh Compose、五服务替换矩阵未执行，不属于本切片的默认本机验收。

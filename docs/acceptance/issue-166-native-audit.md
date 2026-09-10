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
- 双轴审查：Standards 0 项；Spec 1 项 P1 验收缺口，为下述真实来源消费和 IDE 重启恢复尚未执行。未发现明确的实现错误或范围扩张。

## 现场验收

待完成。当前检测到 `compose-audit-service-1` 正在运行；原生实例与容器实例冲突须由开发者处理。尚未取得 IDE Audit 启动、正式来源操作对应 Audit Record，以及 IDE 停止/再次启动后的积压消费证据。

现场验收不得以应用健康、配置测试或构造事件替代；不得通过重置 Kafka offset、数据库或开发者数据获得成功。完整仓库 CI、Fresh Compose、五服务替换矩阵未执行。Issue #166 尚不能声明全部验收通过或关闭。

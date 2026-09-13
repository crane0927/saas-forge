# Audit 原生开发与消费验收

对应 Issue #166，遵循 ADR 0043、0044。Audit 从 IDE 启动；来源服务可沿用当前运行方式。Audit 没有公开查询 API，验收以正式来源操作和受限只读数据库观测关联事实，不手工插入审计记录。

## 独立准备与个人配置

复制 `services/audit-service/src/main/resources/application-local.yaml.example` 为同目录 `application-local.yaml`，已有个人文件不覆盖。IDE 激活 `local`，从 classpath 加载文件，不请求 Nacos 配置中心。个人文件被 Git 忽略，个人文件和模板均不进入发布 JAR。

按实际环境填写以下配置；默认地址仅适用于依赖已映射到本机的情况：

| 环境变量 | 默认值或用途 |
| --- | --- |
| `AUDIT_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/audit_db` |
| `AUDIT_DATABASE_USERNAME` | `audit_app`，保持已有运行账号权限 |
| `AUDIT_DATABASE_PASSWORD` | 必填，应用账号密码 |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:29092`；broker 的 advertised listener 也必须从本机可达 |
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` | `127.0.0.1:8848` / `dev` |
| `NACOS_AUDIT_USERNAME` / `NACOS_AUDIT_PASSWORD` | 必填，Audit 专属工作负载身份 |
| `SAASFORGE_ENVIRONMENT` | 默认 `dev`，必须与来源服务的 topic 环境一致 |
| `AUDIT_HTTP_PORT` / `AUDIT_REGISTER_IP` / `AUDIT_BIND_ADDRESS` | 默认 `8084` / `127.0.0.1` / `127.0.0.1`，仅定义自身实例 |

密码和 Kafka SASL/TLS 材料通过 IDE 环境变量或外部受限 Spring configtree 注入，不写入个人 YAML。使用 configtree 时，在个人文件现有 `spring` 节点下加入 `config.import: configtree:/absolute/path/to/audit-secrets/`，每个文件名为对应属性名；目录权限 700、文件 600。仅提供 Audit 运行需要的身份，不导入整份 Compose 管理员环境。需要 Kafka 认证时使用既有 `spring.kafka.properties.security.protocol`、`sasl.mechanism`、`sasl.jaas.config` 等属性；值从实际环境取得，不降低 broker 的认证策略。

数据库、迁移、Kafka topic/ACL 和 Nacos 身份由独立准备流程完成。管理员使用已有 Audit Flyway 迁移入口；应用始终 `spring.flyway.enabled=false`，不得配置 migrator 或管理员账号。现有 V5 就绪检查需要读取迁移历史，运行账号对 `audit_records` 和 `audit_consumed_events` 仍仅有 SELECT/INSERT。隔离与重放表沿用已有授权，不扩大为通用写权限。

Audit 使用两个既有消费组，不能为了获得验收结果改组名或重置 offset：

| 输入 topic（默认 dev） | 消费组 |
| --- | --- |
| `saasforge.dev.iam-service.events` | `audit-service.iam-session-events` |
| `saasforge.dev.tenant-access-service.events` | `audit-service.tenant-events` |

两个隔离 topic 分别为 `saasforge.dev.audit-service.iam-session-isolations` 和 `saasforge.dev.audit-service.tenant-isolations`。使用已准备的 topic 与最小 ACL。原生实例与容器实例冲突由开发者处理；验证暂停期间积压时，确认没有其他 Audit 实例继续消费。

## IDE Run / Debug / 重启

1. 在 IDEA 同步 Maven，创建 Spring Boot 配置：主类 `io.saasforge.audit.AuditServiceApplication`，classpath `audit-service`，Active profiles `local`。
2. 配置上述环境变量或 configtree，只保留 IDE Build 前置步骤，直接 Run/Debug。无需 package、后台 JAR 或 replace/restore。
3. 检查 `http://127.0.0.1:8084/actuator/health/readiness` 返回 200/UP。它同时要求 Nacos 已确认注册、V5 迁移可见、Kafka 可连接、两个 Consumer 均取得目标分区；liveness 只检查进程存活。这里的内部 HTTP 仅用于健康探测，不是浏览器业务入口。
4. 可在 `IamSessionKafkaConsumer.consume` 或 `TenantAccessKafkaConsumer.consume` 设置断点，观察正式来源事件抵达后继续执行。长时间暂停可能触发 Kafka rebalance，完成消费及就绪恢复后再记录结果。
5. 修改后用 IDEA Build、Stop、Run/Debug 控制生命周期。注册地址变化只影响自身实例，不配置静态下游 HTTP/gRPC 地址。

## 真实来源事件及重启恢复

使用已有可登录的验收账号，通过受信 HTTPS Console 的正式登录操作产生 IAM `SESSION_STARTED` 事实；Console 仍使用共享类型化 HTTP Client，Cookie、Origin、Fetch Metadata 交给浏览器管理。无需新建账号、重置密码或写入审计表。也可使用已有平台 Console 的创建 Tenant 操作验证 `TENANT_CREATED`。

1. 记录操作开始时间，Audit 就绪后执行一次正式登录。用 IAM 的受限只读观测取得该次事件 `event_id`，确认 Outbox 已发布；只保存事件 ID、时间和 topic，不输出完整事件或凭据。

   ```sql
   -- 在 iam_db 执行；以本次操作时间和已知验收 Identity 精确关联，禁止仅比较全表数量。
   SELECT event_id, occurred_at, topic, published_at
   FROM iam_outbox_events
   WHERE occurred_at >= :'operation_started_at'::timestamptz
     AND event_snapshot->>'type' = 'com.saasforge.iam.session.started.v1'
     AND ordering_key = :'identity_id'
   ORDER BY occurred_at DESC;
   ```

2. 在 `audit_db` 用只读连接按该 `event_id` 查询。确认 action、source、resource 与实际来源事实一致，Audit Record 和去重记录各一条。`published_at` 有值只能证明来源发布，不能单独证明消费。

   ```sql
   SELECT source_event_id, source, source_type, action, resource_type, resource_id, result, recorded_at
   FROM audit_records WHERE source_event_id = :'event_id'::uuid;
   SELECT consumer_name, event_id, consumed_at
   FROM audit_consumed_events WHERE event_id = :'event_id'::uuid;
   ```

3. 在 IDEA Stop Audit，确认进程退出且两个消费组没有其他成员。保持数据库、Kafka、Nacos 和来源服务运行；再次通过正式登录产生一个新事实。记录其 Outbox `event_id` 和 `published_at`，确认此时对应 Audit Record 为零。
4. 在 IDEA 以原配置再次 Run/Debug；等待 readiness UP。确认暂停期间的事件最终对应一条 Audit Record、一条去重记录，之前的事件仍各一条。记录 IDE 前后进程、时间和两次事件 ID，不改变 group ID、offset 或数据。
5. 保留上述证据。提交后未确认造成的重投去重由聚焦 Kafka/PostgreSQL 集成测试补充验证，测试构造的事件不能代替步骤 1–4 的真实来源事实。

## 聚焦验证

配置及就绪检查：

```bash
mvn -pl services/audit-service -am \
  -Dtest=LocalConfigurationTest,AuditServiceApplicationTest,AuditRuntimeReadinessHealthIndicatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

消费、重投与追加权限集成测试（使用独立 Testcontainers PostgreSQL/Kafka）：

```bash
mvn -pl services/audit-service -am -DskipTests package
mvn -pl services/audit-service -am \
  -Dit.test=SessionStartedConsumerPostgreSqlKafkaIT \
  -Dfailsafe.failIfNoSpecifiedTests=false failsafe:integration-test failsafe:verify
```

完成改动后运行该模块全套：`mvn -pl services/audit-service -am verify`。这包括所有 Audit 单元与集成测试，不等于完整仓库 CI，也不等于 IDE 现场验收。日常启动不依赖这些打包/验证命令；完整五服务替换矩阵不作为本切片默认验收。结果见 [验收记录](acceptance/issue-166-native-audit.md)。

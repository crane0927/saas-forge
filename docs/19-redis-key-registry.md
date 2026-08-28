# Redis Key Registry

Redis 仅承载安全状态、限流状态和可重建缓存，不承载领域真相。所有平台 Redis Key 必须先登记后使用，登记文件位于 `contracts/redis/registry`，并由 `contracts/redis/registry.schema.json` 约束。

## Key 格式与所有权

Key 统一使用：

```text
sf:<environment>:<owner>:<purpose>:v<schema>:<identifier>
```

- `owner` 是唯一写入所有者；`readers` 显式列出允许读取该 Key 的组件。
- `identifier` 只使用内部 ID 或不可逆摘要，不得包含 Token、密码、Cookie、Secret、邮箱等原始敏感信息。
- 两个领域服务不得共同修改同一个 Key，不得通过 Redis Hash 模拟共享数据库表或跨服务领域实体。
- 跨组件读取仅用于已经冻结的安全基础设施契约；普通领域状态通过 API、版本化事件或各服务自己的缓存传播。
- Key 或值格式发生不兼容变化时必须提升 `schemaVersion`，创建新 Key 版本，并登记双读、双写或失效迁移策略。

代码应通过所属组件的 Key Factory 构造已登记的 Key，不允许散落字符串拼接。业务项目自行选择的 Redis 缓存属于业务项目自己的命名空间、凭据和 Registry，不登记到平台 Registry。

## 登记字段

每项登记至少包含稳定 ID、Key 模式、用途、写入者、读取者、值格式、TTL 规则、最大基数、允许的标识符、失效机制、故障策略和版本迁移策略。具体环境可以调整配置值，但不得弱化登记的安全下限。

## 首版用途与故障策略

| 用途 | 权威来源 | Redis 故障策略 |
|---|---|---|
| JWT `jti` 黑名单 | IAM 撤销事实 | Gateway/Starter fail-closed；TTL 等于 Token 剩余有效期 |
| 撤销 Signing Key `kid` | IAM 密钥状态 | Gateway/Starter fail-closed；TTL 覆盖最长 Access Token 有效期与 JWKS 缓存窗口 |
| 撤销 OAuth Client `client_id` | IAM Client 吊销状态 | IAM Token 签发与所有 Service Token 接收端 fail-closed；吊销不可逆，Key 无 TTL并随 Ready=false 重建 |
| Membership/Tenant Revocation Fence | IAM Fence 持久状态 | Gateway/IAM Token 签发 fail-closed；ACTIVE 时无 TTL，仅在匹配原 `revocationRequestId` 时条件删除 |
| Refresh Token/Session 缓存 | PostgreSQL | 受控回源数据库；缓存 TTL 不超过权威记录剩余有效期 |
| 登录保护与认证端点限流 | IAM 安全策略 | fail-closed，避免绕过暴力破解防护 |
| Gateway 普通业务限流 | Gateway 策略 | 使用受限的进程内降级阈值并告警，不无限制放行 |
| 纯性能缓存 | 对应权威服务 | 缓存失效并回源，不因缺失缓存改变领域事实 |

Quota 明确禁止使用 Redis 保存或裁决额度，PostgreSQL 是唯一真相。

## SDK Permission/Feature 缓存

SDK 默认提供有容量上限的进程内短缓存，业务项目可替换为自己的 Redis 实现。未命中、过期、收到 Kafka 失效事件或缓存不可用时，经 Gateway 回源平台权威接口；平台接口也不可用时 fail-closed，不使用已过期的允许结果。

该业务 Redis 不得使用平台 Redis 的命名空间或凭据。SDK 只定义缓存接口、Key 所需维度、值语义、TTL 上限、失效事件和回源规则，不规定业务 Redis 的物理 Key。

## 自动校验

`./mvnw verify` 校验 Registry 的结构、Key 模式、单写者、显式读取者、TTL、最大基数、故障策略及禁止用途。首个 Redis 实现必须同步增加 TTL、Key Factory、单写者行为和故障路径的集成测试。

# Tenant Access 跨服务工作流事件

本文件定义[跨服务工作流契约](../../docs/18-tenant-access-cross-service-workflows.md)涉及的首批 CloudEvents JSON 类型。所有事件由其拥有数据的服务在本地事务提交后通过 Outbox 发布；Kafka 交付至少一次，消费者必须以 CloudEvents `id`（本文称事件 ID）幂等。

## 共同约束

每个事件使用版本化 `type`、唯一 CloudEvents `id`、CloudEvents `source`、CloudEvents `time` 和可选 `traceId` 扩展。`data` 仅可包含下表列出的标识符、状态和时间字段。密码、邮箱原文、Invitation 激活令牌、Access/Refresh Token、Client Secret 和 Cookie 不得进入事件。

| 类型 | 生产者 | `data` 白名单 |
|---|---|---|
| `com.saasforge.tenant.created.v1` | Tenant Access | `tenantId`、`status`、`actorIdentityId` |
| `com.saasforge.tenant.administrator-initialized.v1` | Tenant Access | `tenantId`、`membershipId`、`identityId`、`roleId`、`status`、`actorIdentityId` |
| `com.saasforge.invitation.accepted.v1` | Tenant Access | `invitationId`、`tenantId`、`membershipId`、`identityId`、`actorIdentityId` |
| `com.saasforge.iam.tenant-context-switched.v1` | IAM | `identityId`、`previousMembershipId`、`membershipId`、`tenantId` |
| `com.saasforge.membership.disabled.v1` | Tenant Access | `membershipId`、`tenantId`、`identityId`、`actorIdentityId`、`quotaReleasePending` |
| `com.saasforge.tenant.suspended.v1` | Tenant Access | `tenantId`、`actorIdentityId` |
| `com.saasforge.iam.sessions-revoked.v1` | IAM | `revocationRequestId`、`scope`、`membershipId` 或 `tenantId`、`revokedSessionCount` |
| `com.saasforge.quota.consumed.v1` | Entitlement | `tenantId`、`quotaDefinitionId`、`operationId`、`amount` |
| `com.saasforge.quota.released.v1` | Entitlement | `tenantId`、`quotaDefinitionId`、`operationId`、`amount` |

`com.saasforge.iam.sessions-revoked.v1` 是 IAM 已完成安全撤销的事实，不承诺对应 Membership 禁用或 Tenant 冻结也已提交。需要审计完整业务结果的消费者必须分别消费 Tenant Access 的领域事件，不能从会话撤销事件推导领域状态。

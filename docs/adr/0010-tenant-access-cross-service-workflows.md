# Tenant Access 跨服务工作流使用根工作流与同步安全前提

Tenant Access 拥有 Tenant、Membership、Invitation 及其管理员初始化、邀请激活、成员禁用和 Tenant 冻结工作流；IAM 拥有 Identity、凭据、会话和令牌撤销，Entitlement 拥有 Subscription 与 Quota。需要即时安全或额度结论的步骤通过版本化同步契约完成，Kafka 仅发布已提交事实。每个公网变更由接收服务的根工作流和外部幂等键唯一确定，根工作流持久化下游子操作 ID，因而能在没有分布式事务的情况下恢复超时、重启和补偿；这比共享数据库、把 Kafka 当命令总线或异步等待会话撤销更能保持所有权边界和立即拒绝的安全语义。完整流程见[跨服务工作流契约](../18-tenant-access-cross-service-workflows.md)。

# Event contracts

领域事件采用带版本类型名的 CloudEvents JSON。事件只表达来源服务已提交的事实，不能作为跨服务命令或同步流程的成功依据；发布使用 Transactional Outbox，消费者按 CloudEvents `id` 幂等。

Tenant、Invitation、Membership、Tenant 切换、会话撤销与 `max_users` 的首批事件及负载白名单见 [Tenant Access 跨服务工作流](tenant-access-workflows.md)。

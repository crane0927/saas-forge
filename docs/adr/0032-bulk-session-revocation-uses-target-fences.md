# 批量会话撤销使用目标 Revocation Fence

按 Membership 或 Tenant 撤销会话时，IAM 在扫描已签发 Token 前先建立持久 Revocation Fence，并使 Token 签发路径和 Gateway 拒绝该范围；只有在 Fence 下撤销全部既有 Refresh Token Family 和未过期 `jti` 后，IAM 才向 Tenant Access 报告成功。仅扫描 `jti` 无法阻止扫描后、Tenant 或 Membership 状态提交前并发签发的 Token；共享数据库或分布式事务又会破坏服务所有权。Fence 是安全协调边界，不是 Tenant/Membership 领域状态，也不替代 PostgreSQL 撤销事实和逐 `jti` Redis Revocation Index。

Tenant 范围没有会话数上限，因此 IAM 在 Fence 下使用带持久游标的有界批次，每批先写 Redis、再提交 PostgreSQL 撤销事实，全部完成后才终结请求并发布唯一事件。Tenant 恢复时先由 IAM 幂等解除 Fence，再由 Tenant Access 提交 `ACTIVE`；解除 Fence 绝不清除既有 Family 或 `jti` 撤销事实，因此恢复仅允许新会话，不会复活旧 Token。

ACTIVE Fence 在 Redis 中不设 TTL，并在 Revocation Index Ready=false 期间从 PostgreSQL 权威记录重建。Fence 已建立后自动恢复耗尽时，保持 Fence 并要求显式恢复原工作流；不允许新请求覆盖它或以超时为由自动解除。

Fence 解除必须同时绑定新的释放请求 ID、原始撤销请求 ID 和强类型目标，防止迟到的恢复请求删除后续新建的 Fence。Tenant Access 以 `tenantId` 串行化 Tenant 生命周期与成员禁用；Tenant Fence 覆盖整个 Tenant 并优先于 Membership Fence，不将重叠工作流静默合并。

大型撤销的 gRPC 命令每次最多推进一个有界批次并返回 `PENDING` 或 `COMPLETED`；IAM Worker 拥有批次恢复，Tenant Access Worker 只拥有 Tenant 根工作流恢复。运行批次和租约数值由多批次实测后按环境配置，不在领域契约中臆造生产值。

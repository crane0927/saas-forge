# PostgreSQL 是 Quota 额度真相

MVP 的 Quota `consume` 以 PostgreSQL 为唯一额度真相，对每个 `(tenantId, quotaDefinitionId)` 以单条条件更新在 `used < limit` 时才增加一个单位。Redis 不保存或裁决额度真相；该选择确保并发扣减不超额，并使一致性可由数据库集成测试直接验证。

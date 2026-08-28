# Audit Record 不使用 Tenant RLS

`audit_records` 同时保存 Platform 级、Tenant 级和未来可能跨 Tenant 调查的合规记录；来源事实没有 Tenant 时不得伪造 `tenant_id`，查询也不能依赖一个用户 Tenant Context 覆盖 Platform 或跨 Tenant 记录。因此 `audit_records.tenant_id` 可空且不启用 Tenant RLS，Tenant 只表示来源事实明确提供的业务目标。

隔离依靠独立 `audit_db` 与运行账号最小权限：`audit_app` 对 `audit_records` 只有 `SELECT`、`INSERT`，没有 `UPDATE`、`DELETE`、`TRUNCATE`，记录不使用软删除；未来 Audit 查询必须在服务层执行显式 Platform/Tenant 授权，不能把本 ADR 作为通用跨 Tenant 数据访问例外。消费去重、隔离和重放状态使用职责分离的运行表及单独权限，不改变 Audit Record 的只追加语义。

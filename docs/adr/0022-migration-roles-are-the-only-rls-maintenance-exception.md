# 迁移账号是 RLS 的唯一维护例外

Tenant 表始终启用并强制 RLS：`*_app` 只能按事务级 `app.tenant_id` 读写本 Tenant，缺失上下文默认拒绝。为允许版本化数据回填，每张 Tenant 表只向 `*_migrator` 授予 `USING (true) WITH CHECK (true)` 的维护策略；运行时账号既不继承也不能切换到迁移账号，且应用不持有迁移凭据。这样把跨 Tenant 访问限制为短时、受控、可追溯的迁移任务，而不削弱常规请求的 RLS 边界。

# v1 OpenAPI 契约按交付前置依赖冻结

v1 以单一 OpenAPI 根契约维护，并只先冻结实施阶段 2、3 的认证、JWKS、Tenant 管理和所需的最小权益前置链路；Runtime Permission、Feature、Quota 操作以及成员、组织、完整订阅管理仍在对应阶段评审后兼容追加。管理员初始化必须已有有效 Subscription 与 `max_users` 额度，因此 Quota Definition、Plan、Plan Quota Limit 和首个 Subscription 作为该阶段的必要前置而提前纳入；这避免未定义的数据库预置或绕过既有跨服务工作流。

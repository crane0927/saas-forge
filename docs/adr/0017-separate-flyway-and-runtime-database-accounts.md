# Flyway 与运行时使用独立数据库账号

IAM、Tenant Access、Entitlement 与 Audit 各自的逻辑数据库均使用仅供 Flyway 的 `*_migrator` 和仅供服务运行的 `*_app`。迁移账号持有所属 Schema 对象的所有权以执行 DDL；运行时账号不是表所有者、没有 `BYPASSRLS`，且只具备所属服务所需的数据访问权限。该拆分使 RLS 的常规请求隔离不依赖应用自律，同时保留每个服务对其迁移链和数据的独立所有权。

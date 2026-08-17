# PostgreSQL 18 原生生成 UUIDv7 主键

MVP 固定 PostgreSQL 18，并在各服务的首个 Flyway 建表迁移中，以 `DEFAULT uuidv7()` 生成独立实体的 UUIDv7 主键；运行时应用不传入主键，使用 `INSERT ... RETURNING` 取得数据库生成值。此选择让所有服务使用 PostgreSQL 维护的标准 UUIDv7 实现，避免通过扩展、自定义 SQL 函数或应用库引入额外的生成与升级责任；纯关联表和外部调用幂等标识不在此规则之内。

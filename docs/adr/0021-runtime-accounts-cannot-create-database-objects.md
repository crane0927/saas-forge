# 运行时账号不得创建数据库对象

每个服务数据库只使用 `public` Schema；集群引导撤销 `PUBLIC` 的数据库默认权限与 `public` 的 `CREATE`，只向 `*_migrator` 授予建对象权限。业务对象和 Flyway 历史表由迁移账号所有，迁移显式授予 `*_app` 所需的 DML 与序列权限；运行时账号永远不能创建对象。该选择避免可写 `search_path` 形成对象劫持面，同时不为单服务单数据库增加额外 Schema 层级。

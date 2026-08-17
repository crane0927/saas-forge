# Logging Contracts

`application-log.schema.json` 定义结构化应用日志的顶层字段白名单与条件字段，`policy.json` 定义事件、级别、采样和保留类别。人读规则见[应用日志规范](../../docs/20-application-logging.md)。

本仓库组件新增稳定日志事件或字段时必须先更新契约，并通过根目录的 `./mvnw verify`。

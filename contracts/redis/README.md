# Redis Contracts

`registry.schema.json` 定义平台 Redis Key Registry 的机器格式，`registry/` 按唯一写入所有者拆分登记文件。人读规则与故障语义见[Redis Key Registry](../../docs/19-redis-key-registry.md)。

新增或修改 Key 时必须同时更新所属 Registry，并通过根目录的 `./mvnw verify`。业务项目自行选择的 Permission/Feature Redis 缓存不属于平台 Registry。

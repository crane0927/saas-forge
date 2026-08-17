# OpenAPI contracts

REST 契约采用 OpenAPI 3.1，并在接口实现前评审。

[`common.yaml`](common.yaml) 是不含资源路径的公共组件文档，定义成功/失败响应、Problem Details、字段校验、游标分页、异步 Job 与 `Location` 响应头的可复用 Schema、Response 和正反例。资源契约必须通过 `$ref` 或 `allOf` 复用其中组件；例如集合响应以 `allOf` 组合 `Page` 并收窄 `items` 的元素类型，具体 Job 以 `allOf` 组合 `Job` 并定义自身成功结果字段。

[`v1.yaml`](v1.yaml) 是当前资源路径的唯一 OpenAPI 3.1 根契约。它先覆盖实施阶段 2、3 所需的认证、JWKS、Platform Tenant 管理及管理员初始化所需的最小权益前置链路；Runtime Permission、Feature、Quota 操作和其余资源在对应阶段评审后，以向后兼容方式追加到同一 v1 根契约。

# 应用日志规范

Gateway、四个领域服务、官方 Example 以及 SDK/Starter 自身产生的日志统一输出结构化 JSON。容器写标准输出并由 Collector 收集；虚拟机通过 `systemd` 与日志转发收集。应用不依赖本地滚动文件承担留存。

机器可读 Schema 和策略分别位于 `contracts/logging/application-log.schema.json` 与 `contracts/logging/policy.json`。

## 字段模型

每条日志必填：

- `timestamp`、`level`、`service`、`environment`
- 稳定事件名 `event`
- 面向诊断的 `message`
- `schemaVersion`

字段按上下文条件必填：

- 存在 Trace Context 时同时记录 `traceId`、`spanId`。
- HTTP 请求范围内记录 `requestId`，并以 `http` 对象记录方法、路由模板、状态码和耗时。
- 存在租户或身份上下文时，可以记录内部 `tenantId`、`identityId`、`membershipId`、`clientId`。
- 异常记录稳定错误码、异常类型和经过脱敏的消息；堆栈是否输出由环境和级别策略决定。

没有对应上下文时省略字段，不填充空字符串或伪造 ID。事件专有属性放入受控的 `attributes`，只能使用标量值；稳定、反复使用的属性应提升为经评审的 Schema 字段。

## 白名单与脱敏

禁止记录请求或响应体全文，以及密码、Token、Authorization、Cookie、Session Secret、Client Secret、私钥、邮箱和其他原始敏感个人信息。不得把敏感值转移到 `message`、异常消息、堆栈或 `attributes` 绕过字段白名单。

HTTP 路径只记录路由模板，不记录可能携带身份或资源数据的原始 URI。异常输出使用稳定错误码和类型；外部系统原始响应仅能在明确字段审查和脱敏后记录。

## 事件、级别、采样与保留

- `event` 使用稳定、可检索的点分命名，不用任意自然语言代替。
- `ERROR`、`WARN` 和安全拒绝事件不采样。
- 常规 `INFO` 可以按登记事件配置采样率，业务代码不得硬编码采样决策。
- 生产环境默认关闭 `DEBUG`，只允许限时、可审计地针对指定服务开启；生产默认禁用 `TRACE`。
- 同一请求或 Trace 的采样决策保持一致，避免留下不完整链路。
- 策略只登记保留类别，由日志平台按环境配置具体时长，应用不硬编码生产保留天数。

日志 Schema 使用主版本号。新增可选字段属于兼容变更；删除、重命名、改变字段语义或新增必填字段必须提升主版本，并为 Collector 与查询提供兼容窗口。

## 与 Audit Record 的边界

应用日志用于运行诊断，允许按策略采样和到期删除。Audit Record 用于合规与业务追责，只追加、受授权查询并执行独立留存策略。日志不能替代 Audit Record，Audit Record 也不用于保存诊断堆栈。

## 适用范围与自动校验

本规范强制适用于本仓库组件和 SDK/Starter 自身日志。外部业务项目可以复用 Schema 和校验工具，但其领域日志、留存平台及自有字段不由 SaaS Forge 仓库 CI 代为保证。

`./mvnw verify` 校验 Schema、策略、顶层字段白名单、事件名、采样率和敏感字段禁令。首个结构化日志实现必须同步增加真实输出的 Schema 校验、上下文字段测试和敏感值反向测试。

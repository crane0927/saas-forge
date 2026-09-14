# 已发布契约基线

每个 `v1/<git-tag>/` 目录是一次已发布 REST、Protobuf 与事件 v1 契约的不可变快照。`quality-gates` 在 `./mvnw verify` 中将当前 v1 契约与全部快照比较，拒绝结构性破坏。

新增快照只能随发布准备 PR 提交，目录名必须使用待发布 Git tag，且其内容必须是该 PR 当前契约的副本。历史快照不得修改或删除；CI 仅允许在 `v1/` 下新增文件。

## Issue #174：Plan 新授予下限

#170/#174 明确将新 Plan 的 `max_users` 下限从 0 收紧为 1。`CreatePlanRequest` 使用独立 `NewPlanQuotaLimit`，历史 Plan/事件响应保留原零值模型。兼容门禁仅允许该 POST 请求字段的 `minimum` 精确 0→1，并以负向用例防止例外扩散；不修改已发布基线。原 actor/Key 的已提交历史零结果经过稳定响应判定后仍可重放，V6 导入恢复读取所需事实；24 小时后仅保留读取，不制造新授予。既有订阅权益与历史数据库值不改变。

Java SDK 的 `CreatePlanRequest.quotaLimits` 元素类型相应改为 `NewPlanQuotaLimit`，调用方重新编译时需替换旧请求构造类型；响应仍使用 `PlanQuotaLimit`。公共类型白名单仅增加该必要请求模型，未开放 Console 私有恢复接口或内部服务契约。该源码兼容影响与请求范围收紧一并记录，不宣称无破坏性变化。

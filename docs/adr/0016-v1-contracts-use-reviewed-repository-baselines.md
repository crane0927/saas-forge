# v1 契约使用经评审的仓库基线

REST、Protobuf 与事件的 v1 已发布契约以提交在仓库内的快照为兼容性基线，质量门将当前契约与全部历史基线比较并拒绝破坏性变更；基线不可修改，新增快照必须显式评审，以在可复现构建和已发布消费者保护之间取得平衡。每份新快照在发布准备 PR 中以待发布 Git tag 命名，并须与该 PR 的当前契约一致。CI 拒绝修改或删除既有快照，只允许新增快照目录。当前仓库中的 v1 契约是首个受保护基线，门禁只判断此后变更。检查范围包括 `v1.yaml` 及其引用的 `common.yaml`、所有路径和 package 均为 v1 的 Protobuf 契约、以及全部 v1 CloudEvents 信封和 payload schema。门禁自动判定字段、路径、操作、类型、标签、必填项和枚举等结构性破坏；字段语义改变须在契约评审中显式识别并发布新的主版本。v1 不设兼容性豁免：有意不兼容的 REST、Protobuf 或事件契约必须以 v2 并行发布，v1 继续可用直至消费者完成迁移。

`POST /api/v1/auth/tenant-switches` 的认证语义由 [ADR 0031](0031-tenant-context-switch-targets-the-refresh-session.md) 明确覆盖；除此项外，本 ADR 的 v1 基线与无豁免规则保持不变。

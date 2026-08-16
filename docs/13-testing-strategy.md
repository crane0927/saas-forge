# saas-forge 测试策略

## 当前状态

原始立项文档未定义测试策略，因此没有可拆分的测试层级、测试范围、工具选型、测试环境、质量门禁或验收阈值。

## 可验证的产品闭环

立项文档要求 MVP 跑通以下业务闭环：部署平台、平台管理员登录、定义 Feature / Quota、创建 Plan / Tenant / Subscription、初始化 Tenant Admin、租户管理员管理用户/组织/Role、业务服务接入 SDK、解析 Tenant Context、执行 Permission / Feature / Quota Check、执行业务并写入 Audit。

该闭环是产品范围的验证目标，不等同于完整测试策略。具体测试设计待后续补充。

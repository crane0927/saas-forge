# Issue #173：Quota Definition 创建、激活与复用

## 范围

对应 #173，父规格 #170；复用已关闭 #172 的恢复交互。保留全局唯一 `max_users`，不增加其他额度类型或 Plan 管理。

- 正式 `GET /api/v1/platform/quota-definitions` 按编码字面子串、状态及游标查询；详情从 Entitlement 权威读取。
- `quota-definition-operations` 提供当前操作者的列表、详情及原操作恢复。接口重新验证当前 Platform Admin 权限，其他操作者不能读取或恢复记录。
- 新增 V5 前向迁移。登记在独立事务保存；恢复互斥锁覆盖领域状态、原幂等结果、Outbox 与恢复结果的共同提交。未修改历史迁移。
- 原 actor、Key、命令种类和目标共同约束重放。24 小时边界后禁止重放，已提交结果仍可读取；没有稳定结果则显示 UNKNOWN，不能自动新建。
- 创建与激活均由共享类型化 Client 执行。Key 只存在 Runtime 内存 WeakMap，页面不构造或导入 Key，不持久保存请求或恢复材料。
- 独立列表、创建和详情页面采用共享组件及中英文文案。查询确认已有定义时直接复用；读取失败、权限不足、未决提交或非 DRAFT 状态不提供不适用的创建/激活动作。
- Tenant 与 Quota Definition 复用恢复展示组件，不建设跨领域工作流服务。

## 验证记录

本文件随实际验证结果更新；未执行项不视为通过。

- 红灯：新增服务恢复测试因恢复服务尚未存在而编译失败；Runtime 创建/激活两项恢复用例失败；独立页面恢复用例失败。
- 定向通过：Runtime 测试文件 51 项；新增页面文件 4 项；Entitlement Controller 5 项及 PostgreSQL 集成 16 项。数据库验证包括创建/激活稳定结果、真实写入拒绝后恢复、精确 24 小时边界、真实事务锁 PROCESSING、原 Key 拒绝、跨 actor 拒绝和游标作用域。
- 完整前端门禁首次失败于 lint，修复后再次失败于基线语言选择器已使用 `react-dom` 但未登记 peer/白名单；补齐已有依赖声明与锁文件。
- 完整后端门禁首次失败于路由总数仍为 32；新增 5 个正式 operation 后更新为 37，并验证其服务所有权与 USER_REQUIRED 认证要求。
- 初次产品预检使用旧证书，缺少 remote 域名且 443 被占用，未启动验收环境。用户停止 Edge 后，使用既有四域开发证书重新预检通过：Chrome 153.0.8010.36、域名、证书、443 与浏览器 TLS 导航。
- 完整前端、完整后端和真实产品验收：进行中，最终结果待补。

## 真实产品验收入口

`consoles/integration-test/quota-definition-acceptance.mjs` 已接入既有 Fresh Compose 产品套件。Browser plugin not available，沿用 Playwright 的 Chrome 产品渠道。

链路：真实 Chrome → 受信四域 HTTPS → Gateway → Nacos → IAM/Entitlement。通过生产页面创建、激活、复用，服务端提交后丢弃一次响应，刷新、浏览器关闭重启和重新登录后读取原结果；检查双击、编码/状态筛选、英文详情、无障碍、浏览器存储与页面错误。仅注入响应丢失，不伪造成功响应。

当前未推送、未关闭 Issue；本切片完成不代表 #170 或 #165 完成。

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
- 完整后端 `./mvnw -Pbackend-local verify` 通过（6 分 25 秒），包括契约检查、Gateway、服务、SDK、Flyway/Testcontainers 与质量门禁。审查后恢复响应增加原激活目标 ID，执行 Entitlement 全量 `-pl services/entitlement-service -am verify` 复验通过，其中 PostgreSQL 集成 18 项。
- 最终 `pnpm --dir consoles run verify:workspace` 退出码 0：类型、lint、格式、边界、全部 workspace 测试、Chromium 组件/消费者/会话和生产构建通过。Runtime 127 项、Platform Console 21 项（额度页面 11 项）。新增路由导致两条旧路由断言失败，更新后定向复验通过；旧语言控件几何断言要求上下排列，改为任一轴不重叠，保留遮挡、会话和跨标签语言验证。
- 首轮 Fresh Chrome 产品运行 8/10 通过、2 项失败：脚本把数据库权限拒绝的 Gateway 响应错误预期为 500，实际为 502；受限服务日志确认 `permission denied for table quota_definitions`。网络拦截内抛断言导致父测试连带失败。改为拦截仅记录结果并完成请求，临时权限在 finally 恢复后再断言 Gateway 502；后续恢复仍严格要求 200 和原 Key。
- 第二轮在 `tls-ready` 失败，未进入产品测试：容器健康通过，但四域 Chrome 导航均为 ERR_CONNECTION_CLOSED，180 秒内未恢复；环境已自动清理。用相同证书、现有 Node 镜像和回环 443 的最小 TLS 容器复核，curl 及 Chrome 四域均为 200，探针已删除。未修改 TLS 配置或重启开发环境；该间歇连接故障原因未确定。
- 第三轮 TLS 通过，创建回滚后的原 Key 恢复成功；激活请求被 Gateway 以 403 拒绝。定位到原契约没有请求正文，生成客户端没有 JSON Content-Type。新增 Runtime 回归测试先失败（Content-Type 为 null），再通过正式契约声明可选空 JSON 正文并由类型化 Client 发送 `{}`，未放宽 Gateway。Entitlement 全量复验通过，Controller 同时覆盖 JSON 和原无正文调用。
- 修复后的完整前端门禁再次退出码 0（Runtime 128 项）。最后 Standards 复审发现生成 Java 未执行空正文约束，补充激活/恢复入口校验；非空对象、数组、标量不能触发业务，返回 400。Controller 红灯后，Entitlement 全量复验退出码 0，Controller 7 项、PostgreSQL 集成 18 项。
- 第四轮 #173 Chrome 子测试通过（约 7.9 秒），整套 14/22 通过、8 项失败。首个失败为旧 Tenant 用例在 390px 窗口直接等待导航，而当前 Shell 使用默认关闭的抽屉；后续串行用例因未完成语言/会话转换连带失败。更新脚本通过实际导航按钮打开、检查和关闭抽屉，保留 390px，品牌遮挡检查改为当前可见页头。
- 第五轮 #173 再次通过，整套 20/22 通过，仅品牌 Remote 用例及其父项失败：共享 Remote 验证器同样直接点击已关闭抽屉中的链接。补齐两个调用点的实际开导航步骤；审查要求的旧品牌消失断言也调整为抽屉打开时执行。
- 第六轮从最终提交启动完整真实 Chrome/Fresh Compose 复验，结果待补。

## 真实产品验收入口

`consoles/integration-test/quota-definition-acceptance.mjs` 已接入既有 Fresh Compose 产品套件。Browser plugin not available，沿用 Playwright 的 Chrome 产品渠道。

链路：真实 Chrome → 受信四域 HTTPS → Gateway → Nacos → IAM/Entitlement。通过生产页面创建、激活、复用，服务端提交后丢弃一次响应，刷新、浏览器关闭重启和重新登录后读取原结果；检查双击、编码/状态筛选、英文详情、无障碍、浏览器存储与页面错误。仅在隔离项目内临时拒绝数据库 INSERT/UPDATE、恢复权限，并在恢复提交后丢弃响应，不伪造成功响应。

## 独立审查

固定起点 `0f2bc00`。初审发现刷新后未决记录不参与新操作按钮判断（Spec P1）、恢复成功仍残留未知提示（Standards P2）、错误 Tenant 术语（Standards P3）及 ADR 0003 的到期默认规则需要明确例外。均已修复，`3ddcd30` 两轴复审无剩余阻塞项。

页面现在先遍历原操作者的相关恢复记录，未决、过期未知或读取失败均不开放新 Key。真实回滚恢复测试及页面测试覆盖 CREATE/ACTIVATE 的 NOT_COMMITTED、PROCESSING 和 UNKNOWN；已确认 COMMITTED 后清除本地未知提示。ADR 0045 与正式接口说明明确已登记 actor/Key 过期后不从原入口重建，保留可读取的稳定结果。

当前未推送、未关闭 Issue；本切片完成不代表 #170 或 #165 完成。

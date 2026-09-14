# Console 使用 Soybean Admin Element Plus 重构

日期：2026-09-15。
状态：全部实施决策已确认，已按用户最新指示切换为 Vue 3 / Element Plus，已接入首批共享布局源码，业务迁移尚未完成。

## 已确认决策

- 采用 [Soybean Admin Element Plus](https://github.com/soybeanjs/soybean-admin-element-plus) 作为正式项目底座，锁定官方主线提交 `7613bd206cd42001b40e3eafceeb895dcbc277a8`，按实际需要接入布局与组件，裁掉无关示例。
- 沿用 Soybean Admin Element Plus 的布局、主题和页面范式，停止当前自建视觉原型的迭代。精简无关示例功能不等于重新设计界面。
- 保留现有业务能力和后端契约。登录会话、权限、幂等与原操作恢复逻辑需要接入新页面；不以模板的演示认证或模拟接口替换正式业务。
- 延续已确认的产品文案要求：不显示解释设计或布局的小字，避免重复标题，查询和新增操作集中右对齐，返回与关闭使用带可访问名称的图标。
- 之前确定的创建抽屉、Plan 详情抽屉和 Tenant 详情页先保留为业务交互要求；具体接入需要结合 Soybean Admin Element Plus 路由核查。
- 未要求改变 Git 仓库边界，沿用当前单仓库决策；不自动提交或推送。
- 交付目标为两个正式 Console 的全部现有页面迁移。先完成平台端，再迁移租户端；Tenant/Plan 为首批验证页面，完成后继续迁移其他页面。

## 正式页面范围

- 平台端：会话首页、Tenant、Quota Definition、Plan、OAuth Client，以及对应现有创建、详情和操作恢复流程。
- 租户端：工作台，以及共享认证中的登录、初始改密、密码设置、公司选择与切换、退出及恢复流程。
- 当前 Remote 仅有 Design System 消费验收夹具，另有静态 Remote 交付验收入口；按共享边界变化适配并保留验收能力，不将其描述为现有业务模块，也不新增业务 Remote。

## 已确认共享边界

- Soybean Admin Element Plus 承担应用布局、主题、导航和通用 UI。业务页面直接采用其既有组件方式，旧 Design System 的同类布局、表格和表单封装随消费者迁移退出，不再强制套一层旧视觉接口。
- 保留 `api-client` 和无 UI 的 `app-runtime`；它们当前不依赖 React 或旧 Design System，可以继续承载正式 HTTP 与认证状态。
- 现有 React Shell 中的认证与恢复逻辑逐项适配至新页面和路由，不整体嵌入旧 AuthenticationShell。保留脏表单退出保护、原操作者恢复、品牌原子切换等业务和交互语义。
- 两个 Console 共用同一套 Soybean Admin Element Plus 基础能力与版本，分别保留应用入口和安全边界。共享能力的最终目录按上游实际依赖确定，不预先新增多层公开包。
- 旧组件随迁移逐步退出；在全部消费者与验收夹具完成迁移前，不直接删除整个共享包。

## 已确认清除目标

- 最终交付中彻底清除被替代的旧 UI、旧应用壳、路由适配、兼容层、未使用依赖、隔离原型及废弃入口，不保留双轨运行开关或备用旧页面。
- 旧组件专属测试和失效快照随实现退出；业务、安全、键盘、无障碍、国际化和 Remote 交付验证迁移到新实现，不因清理减少必要覆盖。
- 同步清理 AGENTS、设计规范、开发说明、构建和检查脚本中的旧体系要求；淘汰的计划和原型说明在有效回归要求迁入本计划后移除，不长期保留多个相互冲突的工作入口。
- 历史 ADR 的旧约束明确标记被替代并退出当前规范入口；仍有效的认证、品牌与业务边界保留。无需改写 Git 历史来清除工作树中的旧体系。
- 决策记录见 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md)。

## 与现有决策的冲突

- ADR 0047 将此前候选模板定位为视觉参考，并要求沿用自建 A 方案；当前选择已改变该方向。
- ADR 0037 要求所有 UI 通过自建 Design System，且固定了 Ant Design 底层组件及消费者边界。正式采用 Soybean Admin Element Plus 的组件和应用壳由 ADR 0050 明确替代原共享 UI 职责与消费者边界。
- 上述冲突由 ADR 0050 明确替代范围，设计规范和边界检查随消费者迁移同步更新；本计划不静默改写历史 ADR，也保留与 UI 框架无关的业务及安全边界。

## 已确认切换方式

新实现按正式应用完成验收后整体替换其入口；平台端先切换，租户端随后切换。开发期间允许临时保留待迁移代码，最终交付不保留旧体系。

## 不可回归的业务语义

| 边界            | 必须保留                                                                              | 已有测试入口（consoles 下）                                                                       |
| --------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Tenant 创建     | 响应丢失后锁定提交和字段；查询原操作，不生成第二次创建                                | platform-console/test/tenants.test.tsx                                                            |
| Plan 创建       | ACTIVE max_users 前置定义、编码与名称校验、1..2147483647 整数上限；历史零额度仍可读取 | platform-console/test/plans.test.tsx                                                              |
| Plan 操作 guard | 分页查完相关记录；非 COMMITTED 原操作阻止新 Key；读取或游标异常失败关闭               | platform-console/test/plans.test.tsx                                                              |
| 原操作恢复      | 仅 NOT_COMMITTED 且 canReplay 可继续；UNKNOWN/PROCESSING/空列表不重放；不暴露 Key     | shared/react-shell/test/operation-recovery.test.tsx                                               |
| Tenant 局部读取 | 失败不抹掉其他成功区块，不误报无订阅；权威读取不足时禁止初始化                        | platform-console/test/subscriptions.test.tsx、administrator-initialization.test.tsx               |
| 初始化与通知    | 他人不能接管；重发通知不重新初始化；未知状态沿原操作恢复                              | platform-console/test/administrator-initialization.test.tsx、password-setup-notification.test.tsx |
| 生命周期        | 未完成冻结不能提供恢复；保留原错误与恢复语义                                          | platform-console/test/tenant-lifecycle.test.tsx                                                   |
| 导航与并发      | 脏表单退出保护、资源 ID 切换隔离、卸载取消和迟到响应保护                              | platform-console/test/tenants.test.tsx 及相关受影响测试                                           |

已有测试路径只是覆盖线索，尚未执行。若正式改动触及分页 guard 异常、资源切换或迟到响应，应补充针对真实风险的测试；本次已读文件中未确认其专门覆盖。

## 证据与验收原则

- 目标技术栈为 Vue 3、Element Plus、Vue Router、Pinia、TypeScript 和 Vite；实际版本以锁定的官方源码与依赖为准。React 页面和 Hook 重写为 Vue SFC 与 Composable；纯 TypeScript Runtime 和 API Client 复用。
- 已接入 `shared/admin` 的上游布局源码、Vue 会话状态观察器及固定版本依赖；类型检查、3 项认证投影测试和库构建通过。布局夹具在 Chromium 的 1440/1024 宽度验证导航、折叠、跳转焦点与内容遮挡；这不属于正式业务路由或真实接口验收。两个正式应用入口尚未切换。
- 业务不可回归清单已迁入本计划，旧原型与旧计划退出工作树。
- 工作区 `lint`、`build:workspace` 和 113 项边界测试通过；边界测试首次因沙箱禁止监听本机端口失败，放行临时测试端口后全部通过。未进行正式业务路由的真实接口联调。
- 验证正式产品路由及真实业务状态；模板或原型演示、编译通过不等于业务验收完成。
- 本次为技术实施决策，未引入新的领域概念，不向 CONTEXT.md 添加技术术语。

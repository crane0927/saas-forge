# Console UI 与消费边界

当前规范依据 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md)。旧 React / Ant Design 实现已退出正式入口，历史记录不作为当前组件要求。

## 布局与组件

- 使用锁定的 Soybean Admin Element Plus 布局；固定提交与许可证在 `consoles/shared/admin/src/vendor/soybean/UPSTREAM.md`。
- 业务页面直接使用 Element Plus。共享 `admin` 只承担布局、认证、品牌、语言、退出保护和原操作恢复，不再包装整套通用组件库。
- 两个 Console 保留独立入口、固定认证意图及 Origin。Remote 接收 Locale 并继承宿主主题，不安装第二个 Provider，不读写偏好、认证或品牌资料。
- 列表不重复展示导航标题；重置、查询和新增集中右对齐。Tenant/Plan 创建及 Plan 详情使用抽屉；Tenant 详情为独立页面。返回与关闭使用有可访问名称的图标。

## 认证、品牌与业务

- `app-runtime` 持有会话凭据，UI 只观察公开状态并调用类型化 Client。浏览器管理 Cookie、Origin、Fetch Metadata；这些不是 UI 业务参数。
- 品牌必须完整校验名称、颜色、受控素材路径、MIME 与图片解码；取消或过期解析不应用。任何字段失败都整体回退平台品牌，不拼接部分结果。
- 系统浅色/深色、语义状态、文字对比度继续检查。Remote 只读取继承后的 CSS，不独立解析品牌或加载素材。
- 未知提交结果锁定字段和再次提交；原操作恢复只在服务器允许时提供，不生成第二个创建 Key。分页 guard 必须读完，异常或重复游标失败关闭。
- Tenant、订阅、初始化、通知和生命周期分区读取；成功快照可以保留，权威读取不足时禁止相关写入。原操作者边界由共享 Client 和后端验证。

## 语言、键盘和验证

- 支持 `zh-CN`、`en-US`；语言偏好按 Origin 保存，不触发认证。认证与恢复消息按组件模块拆分。
- 所有控件有可访问名称；表单保留错误关联。路由有标题、焦点和 live announcement；抽屉与确认框处理焦点进入、约束、关闭与返回。
- 脏表单离开、退出和公司切换都经过同一退出保护。卸载取消请求并忽略迟到响应。
- 不展示解释布局的小字；保留真实业务风险、失败与恢复信息。
- 执行入口见 [共享前端测试基线](console-testing-baseline.md)。正式产品路由的模拟 HTTP 测试与真实后端验收分别记录。

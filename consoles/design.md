# 前端设计约定

采用锁定的 Soybean Admin Element Plus 布局；组件直接使用 Element Plus。

- 列表页不重复展示导航已有标题，保留可访问标题与路由焦点。
- 重置、查询、新增放在同一个右对齐操作区。
- Tenant/Plan 创建使用抽屉，Plan 详情使用抽屉，Tenant 详情使用独立页面。
- 返回、关闭用图标并提供可访问名称。浮层保留焦点约束和脏表单退出确认。
- 不展示解释布局或实现的小字。业务失败、未知结果与恢复信息必须保留。
- 系统浅色/深色、受控品牌、双语和桌面 Chrome 无障碍继续纳入检查。

依据：[ADR 0050](../docs/adr/0050-consoles-adopt-soybean-element-plus.md)、[Element Plus 设计原则](https://element-plus.org/zh-CN/guide/design.html)。

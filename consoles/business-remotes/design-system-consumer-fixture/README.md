# Design System Remote 消费夹具

该包是 Issue #102 的最小官方 Remote 消费验证，不是产品领域页面，也不实现 Manifest、路由、Runtime Config 或独立生命周期。

`src/remote.tsx` 代表 Remote 代码，只能从 `@saas-forge/design-system` 公共根入口使用组件。它不安装 `DesignSystemProvider`、不导入样式，也不依赖 `antd`。`host/main.tsx` 只代表 Shell 验收宿主：宿主安装唯一 Provider 和全局样式入口，再挂载 Remote，从而验证 Remote 继承当前主题。

缺少公共能力时不得在这里复制组件或添加临时样式，应先按 `shared/design-system/README.md` 的贡献路径扩展 Design System，再升级本包使用同一工作区版本。

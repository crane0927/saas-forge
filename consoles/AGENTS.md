# 前端 Agent 工作目录

本目录保留在 SaaS Forge 单仓库中，继承根目录的公共约束。前端任务以 `consoles` 为工作目录启动，使本目录的 `.agents/skills/` 和 `.codex/config.toml` 生效；从仓库根目录启动的任务不要假定已加载这里的 Skill 或 MCP。

## Ant Design 工具

- 按 [Ant Design 官方 Agent 指南](https://ant.design/docs/react/for-agents-cn/)使用本目录的 `antd` Skill。CLI 是工作区固定版本的开发依赖，先执行 `pnpm install --frozen-lockfile`，再通过 `pnpm exec antd` 调用；不执行 Skill 中的全局安装或自动升级步骤。升级 CLI 时显式修改开发依赖和锁文件。
- 当前组件版本由 `pnpm-workspace.yaml` 中的 `catalog.antd` 定义，为 `6.6.2`。CLI 知识查询显式传入 `--version 6.6.2 --format json`；MCP 使用相同目标版本。CLI 自身版本与组件版本分别管理，不因工具升级而升级组件。
- 修改 Ant Design 封装前先查对应组件 API 和示例，例如 `pnpm exec antd info Button --version 6.6.2 --format json`、`pnpm exec antd demo Button basic --version 6.6.2 --format json`。完成后对改动文件执行 `pnpm exec antd lint <文件> --format json`，并执行受影响模块的现有检查。
- Ant Design 只能在 `shared/design-system` 内使用。Console、React Shell 和业务 Remote 通过 `@saas-forge/design-system` 公共入口使用组件；官方示例不能绕过该边界。共享认证、HTTP 与错误恢复继续遵循根目录规则及 ADR 0039。
- 本目录的项目配置不会自动隐藏用户级 Skill/MCP。不要修改用户全局配置来实现前端工作目录配置。

## design.md 与 LLMs.txt

- 设计布局、配色、排版、间距或公共组件时，先读取项目的 [Design System 规范](../docs/25-design-system.md)，再按需参考 [Ant Design design.md](https://ant.design/design.md)。也可离线执行 `pnpm exec antd design.md --version 6.6.2 --format json`，或调用 MCP 的 `antd_design_md` 工具。
- 官方 design.md 描述 Ant Design 设计语言，其默认主题值不代表 SaaS Forge 的品牌、深色主题或语义 Token。它不能覆盖项目规范、现有 Design System 实现或 ADR 0037；CLI 接受版本参数也不意味着设计文档中的每个值都经过项目验证。
- 查找官方文档时，以 [llms.txt](https://ant.design/llms.txt) 为索引，按任务选择其中的组件、设计或工程 Markdown 文档，优先中文条目。需要完整文档检索时再使用 [llms-full-cn.txt](https://ant.design/llms-full-cn.txt) 或 [llms-full.txt](https://ant.design/llms-full.txt)，不要在每个任务中默认加载全文。
- 样式语义结构资料可从索引中的 [llms-semantic-cn.md](https://ant.design/llms-semantic-cn.md) 进入；具体组件优先通过 `pnpm exec antd semantic <组件> --version 6.6.2 --format json` 或 MCP `antd_semantic` 查询。
- 官方在线文档随上游更新，不是项目锁定版本的契约。涉及具体 API、弃用或版本差异时，使用指定 `6.6.2` 的 CLI/MCP 核对。上述文档通过这些入口按需读取，不复制整份上游文档到仓库，不把 llms.txt 当作会自动生效的客户端配置。

## 使用入口

在 Codex 中选择本目录创建前端任务，或从本目录运行 `codex`。MCP 配置变更后创建新任务或重新启动客户端；已有任务不会因此自动获得工具。

可用 `codex mcp get antd --json` 检查该工作目录解析后的 MCP 配置，用 `pnpm exec antd info Button --version 6.6.2 --format json` 验证 CLI。

<!-- prettier-ignore-start -->

<!-- antd-cli setup start -->
## Ant Design CLI Skill

Use the shared Ant Design skill at `.agents/skills/antd/SKILL.md` before working on Ant Design code in this repository.

The skill teaches agents when and how to call `@ant-design/cli` commands such as `antd info`, `antd doc`, `antd demo`, `antd token`, `antd semantic`, and `antd changelog`.

<!-- antd-cli setup end -->

<!-- prettier-ignore-end -->

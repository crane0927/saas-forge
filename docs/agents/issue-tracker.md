# 问题跟踪：GitHub

本仓库的问题和 PRD 均存放于 GitHub Issues。所有操作使用 `gh` CLI。

## 约定

- **创建问题**：`gh issue create --title "..." --body "..."`。多行正文使用 heredoc。
- **读取问题**：`gh issue view <number> --comments`，并同时获取标签。
- **列出问题**：`gh issue list --state open`，可按标签和状态筛选。
- **评论问题**：`gh issue comment <number> --body "..."`。
- **添加或移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **关闭问题**：`gh issue close <number> --comment "..."`。

在仓库目录中运行时，`gh` 会从 `git remote -v` 自动识别目标仓库。

## Pull requests 作为分诊入口

**PRs as a request surface: no.**

## 当 skill 要求“发布到 issue tracker”时

创建一个 GitHub Issue。

## 当 skill 要求“获取相关 ticket”时

运行 `gh issue view <number> --comments`。

## Wayfinding 操作

`/wayfinder` 使用一个地图 Issue 和多个子 Issue：

- **地图**：一个带有 `wayfinder:map` 标签的 Issue，其中记录 Notes、Decisions-so-far 和 Fog。
- **子 ticket**：关联到地图的 GitHub 子 Issue；若子 Issue 不可用，则在地图 Issue 中使用任务列表，并在子 Issue 正文写入 `Part of #<map>`。使用 `wayfinder:<type>` 标签标识 `research`、`prototype`、`grilling` 或 `task`。
- **阻塞关系**：优先使用 GitHub 原生 Issue 依赖；若不可用，则在子 Issue 正文顶部写入 `Blocked by: #<n>, #<n>`。所有阻塞 Issue 关闭后，该 ticket 才解除阻塞。
- **可执行队列**：列出地图的未关闭子 Issue，排除存在未关闭阻塞项或已分配负责人的 Issue，按地图中的顺序选择第一个。
- **认领**：`gh issue edit <n> --add-assignee @me`。
- **完成**：`gh issue comment <n> --body "<answer>"`，随后关闭 Issue，并将上下文指针追加到地图的 Decisions-so-far。

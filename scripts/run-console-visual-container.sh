#!/usr/bin/env bash
set -Eeuo pipefail
mode="${1:-verify}"
export SF_VISUAL_SNAPSHOTS=true
export SF_BROWSER=chromium
unset SF_BROWSER_CHANNEL
export TZ=UTC
export LANG=C.UTF-8
export PNPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS=false
# 挂载目录由宿主用户拥有；非 root 进程只在自己的临时 HOME 安装启动入口。
mkdir -p "$HOME/bin"
corepack enable --install-directory "$HOME/bin"
export PATH="$HOME/bin:$PATH"
pnpm install --frozen-lockfile
args=()
[[ "$mode" == --update ]] && args+=(--update)
# 两个入口分别保存报告；即使组件检查失败，也保留消费者的独立结果。
set +e
pnpm --filter @saas-forge/design-system exec vitest run --config vitest.browser.config.ts \
  --reporter=default --reporter=json --outputFile=/evidence/components.json "${args[@]}"
components=$?
pnpm exec vitest run --config vitest.consumers.browser.config.ts \
  --reporter=default --reporter=json --outputFile=/evidence/consumers.json "${args[@]}"
consumers=$?
set -e
mkdir -p /evidence/screenshots
# 包含 actual/diff 和候选基线；只复制图片，不复制工作区或运行凭据。
while IFS= read -r -d '' file; do
  mkdir -p "/evidence/screenshots/$(dirname "$file")"
  cp "$file" "/evidence/screenshots/$file"
done < <(find shared/design-system/browser-test browser-test -type f -name '*.png' -print0)
node --version > /evidence/runtime.txt
pnpm exec playwright --version >> /evidence/runtime.txt
[[ "$components" -eq 0 && "$consumers" -eq 0 ]]

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
# 布局、认证、键盘及无障碍检查共用 Vue 浏览器入口。
set +e
pnpm exec vitest run --config vitest.consumers.browser.config.ts \
  --reporter=default --reporter=json --outputFile=/evidence/consumers.json "${args[@]}"
consumers=$?
set -e
mkdir -p /evidence/screenshots
# 包含 actual/diff 和候选基线；只复制图片，不复制工作区或运行凭据。
while IFS= read -r -d '' file; do
  mkdir -p "/evidence/screenshots/$(dirname "$file")"
  cp "$file" "/evidence/screenshots/$file"
done < <(find browser-test -type f -name '*.png' -print0)
node --version > /evidence/runtime.txt
pnpm exec playwright --version >> /evidence/runtime.txt
[[ "$consumers" -eq 0 ]]

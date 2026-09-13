#!/usr/bin/env bash
set -Eeuo pipefail

# 固定镜像摘要与 CPU 架构，避免 macOS/Linux、字体或浏览器升级造成不可复现的图片差异。
readonly image='mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e'
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-verify}"
[[ "$mode" == verify || "$mode" == --update ]] && [[ $# -le 1 ]] || {
  echo '用法：bash scripts/verify-console-visual.sh [--update]；更新仅生成待审阅候选图片' >&2
  exit 2
}
[[ -f "$root/consoles/shared/api-client/.generated/index.ts" ]] || {
  echo '缺少生成 Client；请先执行 pnpm --dir consoles run generate:api' >&2
  exit 1
}
readonly workspace="$(mktemp -d "${TMPDIR:-/tmp}/sf-visual.XXXXXX")"
mkdir -p "$root/.scratch/issue-180-visual" "$root/.scratch/console-visual-store"
readonly evidence="$(mktemp -d "$root/.scratch/issue-180-visual/run.XXXXXX")"
readonly container_name="sf-visual-$(basename "$workspace" | tr '[:upper:]' '[:lower:]')"
cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  rm -rf -- "$workspace"
}
trap cleanup EXIT
# 在临时副本安装 Linux 依赖，不覆盖开发者的 node_modules、配置或图片基线。
tar -C "$root/consoles" --exclude=node_modules --exclude=dist --exclude='.env*' \
  --exclude='.secrets' --exclude='*.local.*' --exclude=.vite --exclude=.git -cf - . \
  | tar -C "$workspace" -xf -
cp "$root/scripts/run-console-visual-container.sh" "$workspace/.run-visual.sh"
printf 'EVIDENCE: %s\n' "$evidence"
set +e
docker run --name "$container_name" --platform linux/arm64 --init --shm-size=1g \
  --cpus=2 --memory=4g \
  --mount "type=bind,src=$workspace,dst=/work" \
  --mount "type=bind,src=$evidence,dst=/evidence" \
  --mount "type=bind,src=$root/.scratch/console-visual-store,dst=/pnpm-store" \
  --env PNPM_CONFIG_STORE_DIR=/pnpm-store \
  --workdir /work --env CI=1 --env SF_VISUAL_SNAPSHOTS=true \
  "$image" bash /work/.run-visual.sh "$mode"
status=$?
set -e
printf '%s\n' "$status" > "$evidence/exit-code.txt"
printf '视觉检查退出码：%s；证据：%s\n' "$status" "$evidence"
exit "$status"

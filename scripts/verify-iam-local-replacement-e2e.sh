#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly lifecycle_tool="$repository_root/scripts/local-service-replacement.sh"
readonly browser_check="$repository_root/consoles/scripts/verify-iam-local-replacement-browser.mjs"

snapshot_images() {
  local image
  for image in \
    saasforge/gateway:local \
    saasforge/iam-service:local \
    saasforge/tenant-access-service:local \
    saasforge/entitlement-service:local \
    saasforge/audit-service:local; do
    docker image inspect --format '{{.Id}}' "$image"
  done
}

readonly image_before="$(snapshot_images)"
cleanup() {
  if ! "$lifecycle_tool" restore iam-service >/dev/null 2>&1; then
    echo "清理失败：请手动运行 bash scripts/local-service-replacement.sh restore iam-service" >&2
  fi
}
trap cleanup EXIT

"$lifecycle_tool" replace iam-service
"$lifecycle_tool" status iam-service
(
  cd "$repository_root/consoles"
  mise exec node@24.14.1 -- node "$browser_check"
)
"$lifecycle_tool" restore iam-service
"$lifecycle_tool" status iam-service

if [[ "$(snapshot_images)" != "$image_before" ]]; then
  echo "本机替换验收期间检测到应用镜像标识变化" >&2
  exit 1
fi

echo "Issue #128 IAM 本机替换与恢复验收通过。"

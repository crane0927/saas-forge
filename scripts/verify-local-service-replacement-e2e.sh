#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly lifecycle_tool="$repository_root/scripts/local-service-replacement.sh"
readonly browser_check="$repository_root/consoles/scripts/verify-local-service-replacement-browser.mjs"
readonly local_https_certificate_authority="$repository_root/deploy/compose/.secrets/local-https-development/root-ca.pem"
readonly target="${1:-}"

case "$target" in
  gateway|tenant-access-service|entitlement-service|audit-service) ;;
  *)
    echo "用法：bash scripts/verify-local-service-replacement-e2e.sh <gateway|tenant-access-service|entitlement-service|audit-service>" >&2
    exit 2
    ;;
esac

for required_file in \
  "${SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE:?SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE is required}" \
  "${SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE:?SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE is required}"; do
  if [[ ! -s "$required_file" ]]; then
    echo "本机替换浏览器验收所需的受限凭据文件不可读或为空。" >&2
    exit 1
  fi
done

assert_platform_https_edge() {
  if [[ ! -s "$local_https_certificate_authority" ]]; then
    echo "本机 HTTPS Edge CA 证书缺失；请先运行 bash scripts/local-https-development.sh setup。" >&2
    exit 1
  fi
  if ! curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
    --cacert "$local_https_certificate_authority" \
    --output /dev/null \
    https://platform.saasforge.test/; then
    echo "本机 HTTPS Edge 无法通过受信 TLS 访问 Platform；请先运行 bash scripts/local-https-development.sh start 后重试。" >&2
    exit 1
  fi
}

compose() {
  local -a arguments=(compose --project-directory "$repository_root/deploy/compose" --file "$repository_root/deploy/compose/compose.yaml")
  if [[ -n "${LOCAL_COMPOSE_ENV_FILE:-}" ]]; then
    arguments+=(--env-file "$LOCAL_COMPOSE_ENV_FILE")
  fi
  if [[ -n "${COMPOSE_PROJECT_NAME:-}" ]]; then
    arguments+=(--project-name "$COMPOSE_PROJECT_NAME")
  fi
  if [[ -n "${LOCAL_COMPOSE_OVERRIDE_FILE:-}" ]]; then
    arguments+=(--file "$LOCAL_COMPOSE_OVERRIDE_FILE")
  fi
  docker "${arguments[@]}" "$@"
}

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

audit_count() {
  compose exec -T postgres sh -eu -c \
    'psql --username "$POSTGRES_USER" --dbname audit_db --tuples-only --no-align --command "SELECT count(*) FROM audit_records WHERE action = '\''SESSION_STARTED'\''"' \
    | tr -d '[:space:]'
}

assert_platform_https_edge

readonly image_before="$(snapshot_images)"
audit_before=""
if [[ "$target" == "audit-service" ]]; then
  audit_before="$(audit_count)"
fi

cleanup() {
  if ! "$lifecycle_tool" restore "$target" >/dev/null 2>&1; then
    echo "清理失败：请手动运行 bash scripts/local-service-replacement.sh restore $target" >&2
  fi
}
trap cleanup EXIT

"$lifecycle_tool" replace "$target"
"$lifecycle_tool" status "$target"
(
  cd "$repository_root/consoles"
  SF_LOCAL_REPLACEMENT_TARGET="$target" mise exec node@24.14.1 -- node "$browser_check"
)

if [[ "$target" == "audit-service" ]]; then
  for _ in $(seq 1 180); do
    if [[ "$(audit_count)" -gt "$audit_before" ]]; then
      break
    fi
    sleep 1
  done
  if [[ "$(audit_count)" -le "$audit_before" ]]; then
    echo "本机 Audit 未持久化浏览器登录触发的 SESSION_STARTED 事实。" >&2
    exit 1
  fi
fi

"$lifecycle_tool" restore "$target"
"$lifecycle_tool" status "$target"
(
  cd "$repository_root/consoles"
  SF_LOCAL_REPLACEMENT_TARGET="$target" mise exec node@24.14.1 -- node "$browser_check"
)

if [[ "$(snapshot_images)" != "$image_before" ]]; then
  echo "本机替换验收期间检测到应用镜像标识变化。" >&2
  exit 1
fi

echo "Issue #130 $target 本机替换与恢复验收通过。"

#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly development_tool="$repository_root/scripts/local-development.sh"
readonly target_check="$repository_root/scripts/verify-local-service-replacement-e2e.sh"
readonly security_check="$repository_root/consoles/scripts/verify-local-development-security-browser.mjs"
readonly -a targets=(
  gateway
  iam-service
  tenant-access-service
  entitlement-service
  audit-service
)

for required_file in \
  "${SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE:?SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE is required}" \
  "${SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE:?SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE is required}"; do
  if [[ ! -s "$required_file" ]]; then
    echo "五服务验收矩阵所需的受限凭据文件不可读或为空。" >&2
    exit 1
  fi
done

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
    printf '%s ' "$image"
    docker image inspect --format '{{.Id}}' "$image"
  done
}

snapshot_volumes() {
  local project_name
  local postgres_container
  postgres_container="$(compose ps --quiet postgres)"
  if [[ -z "$postgres_container" ]]; then
    echo "无法从 Compose PostgreSQL 容器确定项目名称。" >&2
    return 1
  fi
  project_name="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$postgres_container")"
  local volume
  while IFS= read -r volume; do
    [[ -z "$volume" ]] || docker volume inspect --format '{{.Name}}' "$volume"
  done < <(docker volume ls --filter "label=com.docker.compose.project=$project_name" --format '{{.Name}}' | sort)
}

assert_all_container() {
  local status_output
  status_output="$("$development_tool" status)"
  printf '%s\n' "$status_output"
  local target
  for target in "${targets[@]}"; do
    if ! grep -Eq "^STATUS: $target CONTAINER .*readiness=READY nacos=1$" <<<"$status_output"; then
      echo "最终拓扑异常：$target 未由唯一健康 Compose 容器提供。" >&2
      exit 1
    fi
  done
}

cleanup() {
  local target
  for target in "${targets[@]}"; do
    "$development_tool" restore "$target" >/dev/null 2>&1 || true
  done
}

assert_all_container
trap cleanup EXIT
readonly image_before="$(snapshot_images)"
readonly volumes_before="$(snapshot_volumes)"
if [[ -z "$volumes_before" ]]; then
  echo "未发现当前 Compose 项目的数据卷；拒绝执行无法证明卷保留的验收。" >&2
  exit 1
fi

for target in "${targets[@]}"; do
  echo "MATRIX: 开始 $target 容器→本机→容器验收。"
  "$target_check" "$target"
  assert_all_container
done

(
  cd "$repository_root/consoles"
  mise exec node@24.14.1 -- node "$security_check"
)

assert_all_container
if [[ "$(snapshot_images)" != "$image_before" ]]; then
  echo "验收矩阵期间检测到应用镜像标识变化。" >&2
  exit 1
fi
if [[ "$(snapshot_volumes)" != "$volumes_before" ]]; then
  echo "验收矩阵期间检测到 Compose 数据卷集合变化。" >&2
  exit 1
fi

trap - EXIT
echo "Issue #131 五服务本机替换验收矩阵通过；应用镜像与数据卷保持不变。"

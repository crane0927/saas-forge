#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"
readonly override_file="$compose_directory/console-authentication.override.yaml"
readonly acceptance_target="${SF_ACCEPTANCE_TARGET:-local}"
[[ "$acceptance_target" == local || "$acceptance_target" == ci ]] || {
  echo 'SF_ACCEPTANCE_TARGET 必须是 local 或 ci' >&2
  exit 2
}

if [[ "${1:-}" != "" && "${1:-}" != "--preflight" && "${1:-}" != "--product" ]] || [[ "$#" -gt 1 ]]; then
  echo '用法：bash scripts/verify-console-authentication-e2e.sh [--preflight|--product]' >&2
  exit 2
fi
for required_command in node pnpm docker openssl ruby; do
  command -v "$required_command" >/dev/null || {
    echo "BLOCKED: 缺少 $required_command" >&2
    exit 1
  }
done
export PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=false
(cd "$repository_root/consoles" && node scripts/check-console-authentication-environment.mjs)
docker info --format '{{.ServerVersion}}' >/dev/null
[[ "$(cd "$repository_root/consoles" && pnpm --version)" == '11.22.0' ]] || {
  echo 'BLOCKED: 需要 pnpm 11.22.0' >&2
  exit 1
}
[[ "${1:-}" == '--preflight' ]] && exit 0

readonly project_name="saas-forge-console-$(date +%s)-$$-$(openssl rand -hex 3)"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"
readonly environment_file="$work_directory/compose.env"
compose_started=false

compose() {
  docker compose --ansi never --progress quiet \
    --project-directory "$compose_directory" --env-file "$environment_file" \
    --project-name "$project_name" --file "$compose_directory/compose.yaml" \
    --file "$override_file" "$@"
}

cleanup() {
  local exit_code="$?"
  trap - EXIT
  if [[ "$compose_started" == true ]]; then
    # 只清理本次随机项目；不操作开发环境或其他验收项目的数据卷。
    if ! compose down --volumes --remove-orphans >"$work_directory/cleanup.log" 2>&1; then
      printf 'FAIL: 清理项目 %s 失败，请检查该项目的剩余资源\n' "$project_name" >&2
      exit_code=1
    fi
  fi
  for service in gateway iam-service tenant-access-service entitlement-service audit-service; do
    docker image rm "$project_name/$service:acceptance" >/dev/null 2>&1 || true
  done
  rm -rf "$work_directory"
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

stage() {
  local name="$1"
  shift
  printf 'RUN: %s\n' "$name"
  if ! "$@" >"$work_directory/$name.log" 2>&1; then
    # 原始服务/构建日志可能携带凭据，不能直接输出到终端或公开验收记录。
    local diagnostic_directory
    diagnostic_directory="$(mktemp -d "${TMPDIR:-/tmp}/sf-console-e2e-diagnostics.XXXXXX")"
    cp "$work_directory/$name.log" "$diagnostic_directory/$name.log"
    if [[ "$name" == product-* ]]; then
      node "$repository_root/consoles/scripts/summarize-authentication-failure.mjs" "$work_directory/$name.log"
      compose ps --format json >"$diagnostic_directory/compose-status.json" 2>&1 || true
      compose logs --no-color console-tls >"$diagnostic_directory/tls.log" 2>&1 || true
      compose logs --no-color gateway iam-service entitlement-service >"$diagnostic_directory/services.log" 2>&1 || true
    fi
    printf 'FAIL: %s；受限诊断日志：%s/%s.log（不得直接上传原始日志）\n' \
      "$name" "$diagnostic_directory" "$name" >&2
    return 1
  fi
  printf 'PASS: %s\n' "$name"
  if [[ "$name" == product-* ]]; then
    # 仅输出 TAP 统计；原始诊断继续保存在受限目录，不能把凭据带入 CI 日志。
    awk '/^# (tests|pass|fail|cancelled|skipped|todo|duration_ms) / {print}' "$work_directory/$name.log"
  fi
}

write_environment() {
  mkdir -p "$secret_directory"
  "$compose_directory/generate-service-client-secrets.sh" "$secret_directory" >/dev/null
  printf '%s\n' 'platform-admin@saasforge.test' >"$secret_directory/platform-admin-email"
  openssl rand -base64 32 >"$secret_directory/platform-admin-password"
  cp "$SF_ACCEPTANCE_TLS_CERT" "$secret_directory/tls-cert.pem"
  cp "$SF_ACCEPTANCE_TLS_KEY" "$secret_directory/tls-key.pem"
  {
    printf 'SF_ACCEPTANCE_PROJECT=%s\n' "$project_name"
    printf 'SF_ACCEPTANCE_TLS_CERT=%s\n' "$secret_directory/tls-cert.pem"
    printf 'SF_ACCEPTANCE_TLS_KEY=%s\n' "$secret_directory/tls-key.pem"
    printf 'POSTGRES_ADMIN_USER=saasforge_console_e2e\n'
    for variable in POSTGRES_ADMIN_PASSWORD IAM_MIGRATOR_PASSWORD IAM_APP_PASSWORD \
      TENANT_ACCESS_MIGRATOR_PASSWORD TENANT_ACCESS_APP_PASSWORD ENTITLEMENT_MIGRATOR_PASSWORD \
      ENTITLEMENT_APP_PASSWORD AUDIT_MIGRATOR_PASSWORD AUDIT_APP_PASSWORD REDIS_PASSWORD \
      NACOS_BOOTSTRAP_PASSWORD NACOS_PUBLISH_PASSWORD NACOS_IAM_PASSWORD \
      NACOS_TENANT_ACCESS_PASSWORD NACOS_ENTITLEMENT_PASSWORD NACOS_AUDIT_PASSWORD \
      NACOS_GATEWAY_PASSWORD NACOS_AUTH_IDENTITY_VALUE; do
      printf '%s=%s\n' "$variable" "$(openssl rand -hex 24)"
    done
    for component in PUBLISH IAM TENANT_ACCESS ENTITLEMENT AUDIT GATEWAY; do
      printf 'NACOS_%s_USERNAME=console-e2e-%s\n' "$component" "$component"
    done
    printf 'NACOS_AUTH_IDENTITY_KEY=console-e2e-identity\n'
    printf 'NACOS_AUTH_TOKEN=%s\n' "$(openssl rand -base64 48 | tr -d '\n')"
    printf 'E2E_HOST_GID=%s\n' "$(id -g)"
    printf 'E2E_HOST_UID=%s\n' "$(id -u)"
    printf 'IAM_JWT_ISSUER=https://api.saasforge.test\n'
    printf 'IAM_JWT_PEM_KEY_VERSION_REF=local/console-e2e/pem/1\n'
    printf 'IAM_JWT_PEM_PRIVATE_KEY_FILE=%s\n' "$secret_directory/iam-jwt-private-key.pem"
    printf 'IAM_PLATFORM_ADMIN_EMAIL_FILE=%s\n' "$secret_directory/platform-admin-email"
    printf 'IAM_PLATFORM_ADMIN_PASSWORD_FILE=%s\n' "$secret_directory/platform-admin-password"
    printf 'IAM_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/iam-client-id"
    printf 'IAM_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/iam-client-secret"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/tenant-access-client-id"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/tenant-access-client-secret"
    printf 'ENTITLEMENT_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/entitlement-client-id"
    printf 'ENTITLEMENT_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/entitlement-client-secret"
  } >"$environment_file"
  chmod 0640 "$secret_directory"/*
}

build_runtime_image() {
  local service="$1" module="$2"
  local image_directory="$work_directory/images/$service"
  local candidate
  local -a jars=()
  mkdir -p "$image_directory"
  for candidate in "$repository_root/$module/target/"*.jar; do
    [[ -f "$candidate" && "$candidate" != *-test-fixture.jar ]] && jars+=("$candidate")
  done
  [[ "${#jars[@]}" -eq 1 ]] || return 1
  cp "${jars[0]}" "$image_directory/application.jar"
  docker build --pull=false --quiet --tag "$project_name/$service:acceptance" \
    --file "$compose_directory/Dockerfile.prebuilt" "$image_directory" >/dev/null
}

write_environment
# Compose 的进程环境优先于 --env-file，必须明确使用本次复制的材料与镜像命名空间。
export SF_ACCEPTANCE_PROJECT="$project_name"
export SF_ACCEPTANCE_TLS_CERT="$secret_directory/tls-cert.pem"
export SF_ACCEPTANCE_TLS_KEY="$secret_directory/tls-key.pem"
export SF_INITIAL_PASSWORD_FILE="$secret_directory/platform-admin-password"
stage compose-config compose config --quiet
[[ -z "$(docker volume ls --quiet --filter "label=com.docker.compose.project=$project_name")" ]] || {
  echo 'FAIL: 验收项目已经存在数据卷，拒绝复用' >&2
  exit 1
}
printf 'ENV: project=%s node=%s date=%s\n' "$project_name" "$(node --version)" "$(date -u +%FT%TZ)"

if [[ "${1:-}" != '--product' ]]; then
  # 根 verify 的 contracts/openapi 门禁已经执行 Console workspace 验证及独立构建。
  stage maven-verify "$repository_root/mvnw" -f "$repository_root/pom.xml" \
    --batch-mode --no-transfer-progress verify
else
  echo 'SCOPE: 重跑产品与浏览器门禁，复用已构建的工件；本次没有执行 Maven/workspace 质量门禁。'
  for application in platform-console tenant-console-shell; do
    [[ -f "$repository_root/consoles/$application/dist/index.html" ]] || {
      echo 'BLOCKED: 缺少生产构建，请先执行完整验收入口' >&2
      exit 1
    }
  done
fi
stage acceptance-client-build node "$repository_root/consoles/scripts/build-authentication-acceptance-client.mjs"
for service in gateway iam-service tenant-access-service entitlement-service audit-service; do
  module="services/$service"
  [[ "$service" == gateway ]] && module=gateway
  stage "image-$service" build_runtime_image "$service" "$module"
done

start_fresh_environment() {
  compose_started=true
  stage signing-key env COMPOSE_PROJECT_NAME="$project_name" \
    LOCAL_COMPOSE_ENV_FILE="$environment_file" LOCAL_COMPOSE_OVERRIDE_FILE="$override_file" \
    bash "$repository_root/scripts/initialize-local-iam-signing-key.sh"
  chmod 0640 "$secret_directory"/*
  stage platform-bootstrap compose --profile bootstrap run --rm iam-platform-admin-bootstrap
  stage service-bootstrap compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
  stage compose-start compose up --detach --wait --wait-timeout 240 console-tls
  # 容器内健康不能证明宿主 443 转发及 TLS 已就绪；实际入口必须通过正常证书验证。
  stage tls-ready node --input-type=module - <<'JS'
const urls = [
  'https://platform.saasforge.test/',
  'https://console.saasforge.test/',
  'https://api.saasforge.test/.well-known/jwks.json',
];
const deadline = Date.now() + 30_000;
let ready = false;
const observations = new Map();
while (!ready && Date.now() < deadline) {
  ready = (await Promise.all(urls.map(async url => {
    try {
      const response = await fetch(url, {
        headers: {accept: url.includes('/.well-known/') ? 'application/json' : 'text/html'},
        signal: AbortSignal.timeout(3_000),
      });
      await response.arrayBuffer();
      observations.set(new URL(url).hostname, response.status);
      return response.status === 200;
    } catch (error) {
      const code = error?.cause?.code;
      observations.set(new URL(url).hostname, /^[A-Z_]+$/.test(code ?? '') ? code : 'NETWORK_UNAVAILABLE');
      return false;
    }
  }))).every(Boolean);
  if (!ready) await new Promise(resolve => setTimeout(resolve, 500));
}
if (!ready) {
  console.info(JSON.stringify(Object.fromEntries(observations)));
  throw new Error('host HTTPS entrypoints did not become ready');
}
console.info('All three host HTTPS entrypoints returned 200 with certificate verification');
JS
}

engines=(chromium webkit)
channels=(chrome)
if [[ "$acceptance_target" == ci ]]; then
  engines+=(firefox)
  channels+=(msedge)
fi
if [[ -n "${SF_PRODUCT_CHANNEL:-}" ]]; then
  [[ "${1:-}" == '--product' && "$acceptance_target" == local ]] || {
    echo 'SF_PRODUCT_CHANNEL 仅用于本地 --product 聚焦验证' >&2
    exit 2
  }
  case "$SF_PRODUCT_CHANNEL" in
    chromium|webkit) engines=("$SF_PRODUCT_CHANNEL"); channels=() ;;
    chrome) engines=(); channels=(chrome) ;;
    *) echo 'SF_PRODUCT_CHANNEL 必须是 chromium、webkit 或 chrome' >&2; exit 2 ;;
  esac
  printf 'SCOPE: 仅执行 %s 产品切片；不执行其他渠道或兼容门禁。\n' "$SF_PRODUCT_CHANNEL"
fi
for engine in "${engines[@]}"; do
  start_fresh_environment
  stage "product-$engine" env SF_BROWSER="$engine" SF_BROWSER_CHANNEL= \
    node --test --test-reporter=tap "$repository_root/consoles/integration-test/console-authentication.test.mjs"
  stage compose-reset compose down --volumes --remove-orphans
done
for channel in "${channels[@]}"; do
  start_fresh_environment
  stage "product-$channel" env SF_BROWSER=chromium SF_BROWSER_CHANNEL="$channel" \
    node --test --test-reporter=tap "$repository_root/consoles/integration-test/console-authentication.test.mjs"
  stage compose-reset compose down --volumes --remove-orphans
done
if [[ -n "${SF_PRODUCT_CHANNEL:-}" ]]; then
  echo 'PASS: 聚焦产品用例通过；本命令不包含其他渠道或 Maven/workspace 门禁。'
  exit 0
elif [[ "$acceptance_target" == ci ]]; then
  stage console-browser-compatibility pnpm --dir "$repository_root/consoles" run test:browser:compatibility
else
  stage console-browser-chrome pnpm --dir "$repository_root/consoles" run test:browser:chrome
  stage console-browser-webkit pnpm --dir "$repository_root/consoles" run test:browser:webkit
  echo 'PENDING: Firefox 与 Edge 的真实产品证据由 GitHub CI 提供。'
fi

if [[ "${1:-}" == '--product' ]]; then
  echo "PASS: $acceptance_target 的产品与浏览器门禁通过；本命令没有执行 Maven/workspace 门禁。"
else
  echo "PASS: $acceptance_target 的 Maven/workspace、生产构建、Fresh Compose 与浏览器门禁全部通过。"
fi

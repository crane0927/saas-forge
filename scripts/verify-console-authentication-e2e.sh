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

# 旧聚焦变量不能重新启用已退出当前支持范围的产品渠道。
if [[ -n "${SF_PRODUCT_CHANNEL:-}" && "$SF_PRODUCT_CHANNEL" != chrome ]]; then
  echo 'SF_PRODUCT_CHANNEL 当前仅接受 chrome；产品验收仅支持 Google Chrome' >&2
  exit 2
fi

if [[ "${1:-}" != "" && "${1:-}" != "--preflight" && "${1:-}" != "--product" && "${1:-}" != "--development" ]] || [[ "$#" -gt 1 ]]; then
  echo '用法：bash scripts/verify-console-authentication-e2e.sh [--preflight|--product|--development]' >&2
  exit 2
fi
runtime_jar() {
  local module="$1" candidate
  local -a jars=()
  for candidate in "$repository_root/$module/target/"*.jar; do
    [[ -f "$candidate" && "$candidate" != *-test-fixture.jar ]] && jars+=("$candidate")
  done
  [[ "${#jars[@]}" -eq 1 ]] || {
    echo "BLOCKED: $module 必须恰有一个运行 JAR，请先执行完整 Maven verify 并检查旧制品" >&2
    return 1
  }
  printf '%s\n' "${jars[0]}"
}

# CI 在同一 job 先执行完整 verify；复用入口必须在环境初始化前拒绝缺失或歧义制品。
# 此处只验证存在性，实际构建、镜像启动和浏览器门禁仍负责验证制品可用性。
if [[ "${1:-}" == '--product' ]]; then
  for application in platform-console tenant-console-shell; do
    [[ -f "$repository_root/consoles/$application/dist/index.html" ]] || {
      echo "BLOCKED: 缺少 $application 生产构建，请先执行完整 Maven verify" >&2
      exit 1
    }
  done
  [[ -f "$repository_root/consoles/dist/static-remote-acceptance/v1/remote.js" ]] || {
    echo 'BLOCKED: 缺少 Remote 静态构建，请先执行完整 Maven verify' >&2
    exit 1
  }
  for module in gateway services/iam-service services/tenant-access-service services/entitlement-service services/audit-service; do
    runtime_jar "$module" >/dev/null
  done
fi
# Node 与浏览器均使用系统信任；不忽略证书错误。
export NODE_USE_SYSTEM_CA=1
if [[ "${1:-}" == '--development' ]]; then
  export SF_SECURITY_EDGE_CONTAINER="${SF_SECURITY_EDGE_CONTAINER:-$(docker ps --quiet --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-compose}" --filter "label=com.docker.compose.service=local-https-edge" || true)}"
  exec node "$repository_root/consoles/scripts/verify-development-browser-matrix.mjs"
fi
for required_command in node pnpm docker openssl ruby; do
  command -v "$required_command" >/dev/null || {
    echo "BLOCKED: 缺少 $required_command" >&2
    exit 1
  }
done
export PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=false
export SF_ACCEPTANCE_ROOT_DOMAIN="${SF_ACCEPTANCE_ROOT_DOMAIN:-saasforge.test}"
export SF_BRAND_EVIDENCE_DIRECTORY="${SF_BRAND_EVIDENCE_DIRECTORY:-$(mktemp -d "${TMPDIR:-/tmp}/sf-brand-evidence.XXXXXX")}"
export SF_ACCEPTANCE_SCOPE="${1:---full}"
record_result() { node "$repository_root/consoles/scripts/record-authentication-acceptance.mjs" "$@"; }
printf 'EVIDENCE: %s\n' "$SF_BRAND_EVIDENCE_DIRECTORY"
record_result preflight running
trap 'record_result complete blocked' EXIT
if ! (cd "$repository_root/consoles" && node scripts/check-console-authentication-environment.mjs); then
  record_result preflight blocked
  record_result complete blocked
  exit 1
fi
record_result preflight passed
docker info --format '{{.ServerVersion}}' >/dev/null
[[ "$(cd "$repository_root/consoles" && pnpm --version)" == '11.22.0' ]] || {
  echo 'BLOCKED: 需要 pnpm 11.22.0' >&2
  exit 1
}
if [[ "${1:-}" == '--preflight' ]]; then
  record_result complete passed
  trap - EXIT
  exit 0
fi

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
  if [[ "$exit_code" == 0 ]]; then record_result complete passed; else record_result complete failed; fi
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

stage() {
  local name="$1"
  shift
  record_result "$name" running
  printf 'RUN: %s\n' "$name"
  if ! "$@" >"$work_directory/$name.log" 2>&1; then
    # 原始服务/构建日志可能携带凭据，不能直接输出到终端或公开验收记录。
    local diagnostic_directory
    diagnostic_directory="$(mktemp -d "${TMPDIR:-/tmp}/sf-console-e2e-diagnostics.XXXXXX")"
    cp "$work_directory/$name.log" "$diagnostic_directory/$name.log"
    if [[ "$name" == product-* || "$name" == console-browser-* || "$name" == maven-verify || "$name" == tls-ready ]]; then
      node "$repository_root/consoles/scripts/summarize-authentication-failure.mjs" "$work_directory/$name.log"
    fi
    if [[ "$name" == product-* || "$name" == compose-start || "$name" == tls-ready ]]; then
      compose ps --all --format json >"$diagnostic_directory/compose-status.json" 2>&1 || true
      node "$repository_root/consoles/scripts/summarize-compose-status.mjs" "$diagnostic_directory/compose-status.json"
      compose logs --no-color nacos-init >"$diagnostic_directory/nacos-init.log" 2>&1 || true
      compose logs --no-color console-tls >"$diagnostic_directory/tls.log" 2>&1 || true
      compose logs --no-color gateway iam-service tenant-access-service entitlement-service audit-service >"$diagnostic_directory/services.log" 2>&1 || true
      local console_tls_container
      console_tls_container="$(compose ps --quiet console-tls 2>/dev/null || true)"
      if [[ -n "$console_tls_container" ]]; then
        # 此健康探针仅记录内部端点与 HTTP 状态，仍只保存在受限目录。
        docker inspect --format '{{range .State.Health.Log}}{{printf "%s" .Output}}{{end}}' \
          "$console_tls_container" >"$diagnostic_directory/console-tls-health.log" 2>&1 || true
      fi
    fi
    printf 'FAIL: %s；受限诊断日志：%s/%s.log（不得直接上传原始日志）\n' \
      "$name" "$diagnostic_directory" "$name" >&2
    record_result "$name" failed
    return 1
  fi
  record_result "$name" passed
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
    printf 'SF_ACCEPTANCE_ROOT_DOMAIN=%s\n' "$SF_ACCEPTANCE_ROOT_DOMAIN"
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
  local application_jar
  application_jar="$(runtime_jar "$module")" || return 1
  mkdir -p "$image_directory"
  cp "$application_jar" "$image_directory/application.jar"
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
  export SF_SECURITY_EDGE_CONTAINER="$(compose ps --quiet console-tls)"
  stage tls-ready node --input-type=module - <<'JS'
import { chromium } from './consoles/node_modules/playwright/index.mjs';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN;
const urls = [
  `https://platform.${rootDomain}/`,
  `https://console.${rootDomain}/`,
  `https://api.${rootDomain}/.well-known/jwks.json`,
  `https://remote.${rootDomain}/static-acceptance/v1/remote.js`,
];
// Compose health 与宿主端口转发异步收敛；仍要求四入口在浏览器正常证书校验下均返回 200。
const deadline = Date.now() + 180_000;
const observations = new Map();
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  let ready = false;
  while (!ready && Date.now() < deadline) {
    ready = (
      await Promise.all(
        urls.map(async (url) => {
          const page = await context.newPage();
          try {
            const response = await page.goto(url, {
              waitUntil: 'domcontentloaded',
              timeout: 5_000,
            });
            const status = response?.status() ?? 'NO_RESPONSE';
            observations.set(new URL(url).hostname, status);
            return status === 200;
          } catch (error) {
            // 仅保留 Chromium 网络错误码或固定分类，避免原始异常携带页面数据。
            const code = error?.message?.match(/\bnet::(ERR_[A-Z0-9_]+)\b/)?.[1];
            const category = error?.name === 'TimeoutError'
              ? 'BROWSER_NAVIGATION_TIMEOUT'
              : 'BROWSER_NAVIGATION_UNAVAILABLE';
            observations.set(new URL(url).hostname, code ?? category);
            return false;
          } finally {
            await page.close().catch(() => undefined);
          }
        }),
      )
    ).every(Boolean);
    if (!ready) await new Promise((resolve) => setTimeout(resolve, 500));
  }
  if (!ready) {
    console.info(JSON.stringify(Object.fromEntries(observations)));
    throw new Error('host HTTPS entrypoints did not become ready');
  }
  console.info('All four host HTTPS entrypoints returned 200 with browser certificate verification');
} finally {
  await browser.close();
}
JS
}

# 产品验收仅运行 Chrome；Chromium 的日常功能与视觉检查由 workspace 承担。
start_fresh_environment
stage product-chrome env SF_BROWSER=chromium SF_BROWSER_CHANNEL=chrome \
  node --test --test-reporter=tap "$repository_root/consoles/integration-test/console-authentication.test.mjs"
stage compose-reset compose down --volumes --remove-orphans
# Corepack 根据 cwd 选择 packageManager；pnpm --dir 不会改变 Corepack 的版本解析目录。
(
  cd "$repository_root/consoles"
  stage console-browser-chrome pnpm run test:browser:chrome
)

if [[ "${1:-}" == '--product' ]]; then
  echo "PASS: $acceptance_target 的产品与浏览器门禁通过；本命令没有执行 Maven/workspace 门禁。"
else
  echo "PASS: $acceptance_target 的 Maven/workspace、生产构建、Fresh Compose 与浏览器门禁全部通过。"
fi

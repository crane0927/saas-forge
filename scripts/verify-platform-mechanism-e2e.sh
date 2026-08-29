#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"
readonly override_file="$compose_directory/platform-mechanism-acceptance.override.yaml"
readonly project_name="saas-forge-platform-mechanism-$PPID-$$"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"
readonly environment_file="$work_directory/compose.env"
readonly response_body="$work_directory/response.json"
readonly response_headers="$work_directory/response.headers"
readonly cookie_jar="$work_directory/cookies.txt"
readonly compose_log="$work_directory/compose.log"

gateway_base=""

compose() {
  docker compose --ansi never \
    --progress quiet \
    --project-directory "$compose_directory" \
    --env-file "$environment_file" \
    --project-name "$project_name" \
    --file "$compose_directory/compose.yaml" \
    --file "$override_file" \
    --profile platform-mechanism-acceptance \
    "$@"
}

cleanup() {
  local exit_code="$?"
  compose logs --no-color >"$compose_log" 2>/dev/null || true
  if [[ "$exit_code" -ne 0 ]]; then
    compose ps --all >&2 || true
    compose logs --no-color --tail 500 gateway iam-service platform-mechanism-receiver 2>/dev/null \
      | sed -E 's/((authorization|accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' \
      | rg -i -C 8 'exception|caused by| error ' \
      | tail -n 200 >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work_directory"
  return "$exit_code"
}

on_error() {
  local exit_code="$?"
  printf '平台机制验收失败：line=%s command=%s exit=%s\n' \
    "${BASH_LINENO[0]}" "$BASH_COMMAND" "$exit_code" >&2
  return "$exit_code"
}

random_text() {
  openssl rand -hex 24
}

uuid_v7() {
  local timestamp_ms timestamp_hex random_hex random_a variant_source variant random_b_head random_b_tail
  timestamp_ms="$(ruby -e 'puts (Time.now.to_r * 1000).to_i')"
  timestamp_hex="$(printf '%012x' "$timestamp_ms")"
  random_hex="$(openssl rand -hex 10)"
  random_a="${random_hex:0:3}"
  variant_source="${random_hex:3:1}"
  variant="$(printf '%x' "$(((16#$variant_source & 3) | 8))")"
  random_b_head="${random_hex:4:3}"
  random_b_tail="${random_hex:7:12}"
  printf '%s-%s-7%s-%s%s-%s\n' \
    "${timestamp_hex:0:8}" "${timestamp_hex:8:4}" \
    "$random_a" "$variant" "$random_b_head" "$random_b_tail"
}

write_environment() {
  local nacos_token_source
  nacos_token_source="$(openssl rand -base64 48 | tr -d '\n')"
  umask 077
  mkdir -p "$secret_directory"
  "$compose_directory/generate-service-client-secrets.sh" "$secret_directory" >/dev/null
  printf '%s\n' 'platform-admin@saasforge.test' >"$secret_directory/platform-admin-email"
  openssl rand -base64 32 | tr -d '\n' >"$secret_directory/platform-admin-password"
  printf '\n' >>"$secret_directory/platform-admin-password"

  {
    printf 'POSTGRES_ADMIN_USER=saasforge_e2e\n'
    printf 'POSTGRES_ADMIN_PASSWORD=%s\n' "$(random_text)"
    printf 'IAM_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'IAM_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'TENANT_ACCESS_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'TENANT_ACCESS_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'ENTITLEMENT_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'ENTITLEMENT_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'AUDIT_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'AUDIT_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'REDIS_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_BOOTSTRAP_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_PUBLISH_USERNAME=publisher-e2e\n'
    printf 'NACOS_PUBLISH_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_IAM_USERNAME=iam-e2e\n'
    printf 'NACOS_IAM_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_TENANT_ACCESS_USERNAME=tenant-access-e2e\n'
    printf 'NACOS_TENANT_ACCESS_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_ENTITLEMENT_USERNAME=entitlement-e2e\n'
    printf 'NACOS_ENTITLEMENT_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_AUDIT_USERNAME=audit-e2e\n'
    printf 'NACOS_AUDIT_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_GATEWAY_USERNAME=gateway-e2e\n'
    printf 'NACOS_GATEWAY_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME=receiver-e2e\n'
    printf 'NACOS_PLATFORM_MECHANISM_RECEIVER_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_AUTH_IDENTITY_KEY=identity-key-e2e\n'
    printf 'NACOS_AUTH_IDENTITY_VALUE=%s\n' "$(random_text)"
    printf 'NACOS_AUTH_TOKEN=%s\n' "$(printf '%s' "$nacos_token_source" | openssl base64 -A)"
    printf 'E2E_HOST_GID=%s\n' "$(id -g)"
    printf 'IAM_JWT_ISSUER=https://api.saasforge.test\n'
    printf 'IAM_JWT_PEM_KEY_VERSION_REF=local/e2e/pem/1\n'
    printf 'IAM_JWT_PEM_PRIVATE_KEY_FILE=%s\n' "$secret_directory/iam-jwt-private-key.pem"
    printf 'IAM_PLATFORM_ADMIN_EMAIL_FILE=%s\n' "$secret_directory/platform-admin-email"
    printf 'IAM_PLATFORM_ADMIN_PASSWORD_FILE=%s\n' "$secret_directory/platform-admin-password"
    printf 'IAM_PLATFORM_ADMIN_RESET_REQUEST_ID_FILE=%s\n' "$secret_directory/platform-admin-reset-request-id"
    printf 'IAM_PLATFORM_ADMIN_RESET_PASSWORD_FILE=%s\n' "$secret_directory/platform-admin-reset-password"
    printf 'IAM_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/iam-client-id"
    printf 'IAM_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/iam-client-secret"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/tenant-access-client-id"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/tenant-access-client-secret"
    printf 'ENTITLEMENT_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/entitlement-client-id"
    printf 'ENTITLEMENT_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/entitlement-client-secret"
  } >"$environment_file"
}

build_runtime_image() {
  local module="$1"
  local image="$2"
  local context_directory="$work_directory/images/${module//\//-}"
  local candidate
  local -a runtime_jars=()
  mkdir -p "$context_directory"
  for candidate in "$repository_root/$module/target/"*.jar; do
    [[ -e "$candidate" ]] || continue
    [[ "$candidate" == *-test-fixture.jar ]] && continue
    runtime_jars+=("$candidate")
  done
  if [[ "${#runtime_jars[@]}" -ne 1 ]]; then
    printf '模块 %s 预期一个 Runtime JAR，实际为 %s\n' "$module" "${#runtime_jars[@]}" >&2
    return 1
  fi
  cp "${runtime_jars[0]}" "$context_directory/application.jar"
  docker build --pull=false --quiet --tag "$image" \
    --file "$compose_directory/Dockerfile.prebuilt" "$context_directory" >/dev/null
}

run_bootstrap() {
  local profile="$1"
  local service="$2"
  local log_file="$work_directory/$service.log"
  if ! compose --profile "$profile" run --rm "$service" >"$log_file" 2>&1; then
    sed -E 's/((authorization|accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' \
      "$log_file" | tail -n 120 >&2
    return 1
  fi
}

wait_for_started_instances() {
  local service="$1"
  local expected="$2"
  local count
  for _ in $(seq 1 180); do
    count="$(compose logs --no-color "$service" 2>/dev/null | grep -c 'Started .*Application' || true)"
    if (( count >= expected )); then
      return 0
    fi
    sleep 2
  done
  echo "服务 $service 未达到 $expected 个已启动实例" >&2
  return 1
}

wait_for_gateway() {
  local status
  for _ in $(seq 1 180); do
    status="$(curl --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' \
      "$gateway_base/.well-known/jwks.json" || true)"
    [[ "$status" == "200" ]] && return 0
    sleep 2
  done
  echo "Gateway 未在预期时间内 Ready" >&2
  return 1
}

wait_for_redis_ready() {
  local ready
  for _ in $(seq 1 90); do
    ready="$(compose exec -T redis sh -eu -c '
      redis-cli --no-auth-warning -a "$REDIS_PASSWORD" GET \
        sf:dev:iam-service:revocation-index-ready:v1:state
    ' 2>/dev/null | tr -d '\r' || true)"
    [[ "$ready" == "1" ]] && return 0
    sleep 1
  done
  echo "IAM Revocation Index 未 Ready" >&2
  return 1
}

request() {
  local expected_status="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local bearer="${5:-}"
  local idempotency_key="${6:-}"
  if (( $# > 6 )); then
    shift 6
  else
    set --
  fi
  local status
  local -a arguments=(
    --silent --show-error --request "$method"
    --dump-header "$response_headers" --output "$response_body" --write-out '%{http_code}'
    --header 'Content-Type: application/json'
    --header 'X-SF-CSRF: 1'
    --header 'Origin: https://console.saasforge.test'
    --header 'Sec-Fetch-Site: same-site'
    --cookie "$cookie_jar" --cookie-jar "$cookie_jar"
  )
  [[ -z "$body" ]] || arguments+=(--data-binary "$body")
  [[ -z "$bearer" ]] || arguments+=(--header "Authorization: Bearer $bearer")
  [[ -z "$idempotency_key" ]] || arguments+=(--header "Idempotency-Key: $idempotency_key")
  arguments+=("$@")
  status="$(curl "${arguments[@]}" "$gateway_base$path")"
  if [[ "$status" != "$expected_status" ]]; then
    echo "请求 $method $path 预期 HTTP ${expected_status}，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

direct_receiver_request() {
  local expected_status="$1"
  local port="$2"
  local token="$3"
  shift 3
  local status
  status="$(curl --silent --show-error --request POST \
    --dump-header "$response_headers" --output "$response_body" --write-out '%{http_code}' \
    --header "Authorization: Bearer $token" "$@" \
    "http://127.0.0.1:$port/__test/platform-mechanism")"
  [[ "$status" == "$expected_status" ]] || {
    echo "直连接收端预期 HTTP ${expected_status}，实际为 $status" >&2
    return 1
  }
}

token_request() {
  local expected_status="$1"
  local client_id="$2"
  local client_secret="$3"
  local scope="$4"
  local status
  status="$(curl --silent --show-error --connect-timeout 2 --max-time 30 \
    --dump-header "$response_headers" --output "$response_body" --write-out '%{http_code}' \
    --user "$client_id:$client_secret" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "scope=$scope" \
    "$gateway_base/oauth2/token")"
  [[ "$status" == "$expected_status" ]] || {
    echo "Client Credentials 预期 HTTP ${expected_status}，实际为 $status" >&2
    return 1
  }
}

assert_json() {
  local expression="$1"
  shift
  jq --exit-status "$@" "$expression" "$response_body" >/dev/null || {
    echo "JSON 断言失败：$expression" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  }
}

trap on_error ERR
trap cleanup EXIT
trap 'exit 130' INT TERM

for command in docker curl jq ruby openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "缺少验收依赖命令: $command" >&2
    exit 1
  }
done

write_environment
touch "$cookie_jar"

echo "[1/9] 校验测试 overlay 未污染生产 Registry、OpenAPI、默认 Compose 与 Helm"
if rg -n 'platform-mechanism-receiver|/__test/platform-mechanism' \
    "$repository_root/contracts/services/engineering-registry.json" \
    "$repository_root/contracts/openapi/v1.yaml" \
    "$repository_root/deploy/compose/compose.yaml" \
    "$repository_root/deploy/helm" >/dev/null; then
  echo "平台机制测试接收端进入了生产工程清单" >&2
  exit 1
fi
compose_config="$work_directory/compose-config.json"
compose config --format json >"$compose_config"
jq --exit-status '.services["platform-mechanism-receiver"].profiles == ["platform-mechanism-acceptance"]' \
  "$compose_config" >/dev/null
grep -Fq 'refreshEnabled=false' \
  "$repository_root/test-support/platform-mechanism-receiver/src/main/resources/application.yaml"
if rg -n '@RefreshScope' "$repository_root/test-support/platform-mechanism-receiver/src" >/dev/null; then
  echo "平台机制测试接收端不得通过 Nacos 热更新路由或认证边界" >&2
  exit 1
fi

echo "[2/9] 构建带测试 Catalog overlay 的真实 Gateway、IAM 与 Starter 接收端"
"$repository_root/mvnw" --batch-mode --no-transfer-progress \
  -Pplatform-mechanism-acceptance \
  -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service,services/audit-service,test-support/platform-mechanism-receiver \
  -am package -DskipTests >"$work_directory/maven-package.log"
jq --exit-status '[.routes[] | select(.operationId == "acceptPlatformMechanismServiceToken" and
  .serviceId == "platform-mechanism-receiver" and .credentialRequirement == "SERVICE_REQUIRED" and
  .requiredScopes == ["runtime:read"])] | length == 1' \
  "$repository_root/contracts/http-route-catalog/target/generated-resources/route-catalog/META-INF/saasforge/http-route-catalog.json" >/dev/null
build_runtime_image gateway saasforge/gateway:local
build_runtime_image services/iam-service saasforge/iam-service:local
build_runtime_image services/tenant-access-service saasforge/tenant-access-service:local
build_runtime_image services/entitlement-service saasforge/entitlement-service:local
build_runtime_image services/audit-service saasforge/audit-service:local
build_runtime_image test-support/platform-mechanism-receiver saasforge/platform-mechanism-receiver:acceptance

echo "[3/9] 初始化真实 PostgreSQL、IAM Signing Key、Platform Admin 与 Reserved Client"
COMPOSE_PROJECT_NAME="$project_name" LOCAL_COMPOSE_ENV_FILE="$environment_file" \
LOCAL_COMPOSE_OVERRIDE_FILE="$override_file" \
  bash "$repository_root/scripts/initialize-local-iam-signing-key.sh" >/dev/null
chmod 0640 "$secret_directory"/*
run_bootstrap bootstrap iam-platform-admin-bootstrap
run_bootstrap service-client-bootstrap iam-reserved-service-client-bootstrap

echo "[4/9] 启动真实 IAM、Redis、Nacos、Gateway 和两个 Starter 接收端实例"
compose up --detach iam-service tenant-access-service entitlement-service audit-service >/dev/null
wait_for_started_instances iam-service 1
compose up --detach --scale platform-mechanism-receiver=2 platform-mechanism-receiver >/dev/null
wait_for_started_instances platform-mechanism-receiver 2
compose up --detach gateway >/dev/null
wait_for_started_instances gateway 1
gateway_port="$(compose port gateway 8080 | sed 's/.*://')"
gateway_base="http://127.0.0.1:$gateway_port"
wait_for_gateway
wait_for_redis_ready

echo "[5/9] 真实 IAM 签发 Runtime Service Token，Gateway 与 Starter 双重验收"
initial_password="$(<"$secret_directory/platform-admin-password")"
request 200 POST /api/v1/auth/login \
  "$(jq -cn --arg password "$initial_password" \
    '{email:"platform-admin@saasforge.test",password:$password,contextType:"PLATFORM"}')"
assert_json '.contextState == "PASSWORD_CHANGE_REQUIRED"'
platform_password="Platform-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-changes \
  "$(jq -cn --arg password "$platform_password" '{newPassword:$password}')"
request 200 POST /api/v1/auth/login \
  "$(jq -cn --arg password "$platform_password" \
    '{email:"platform-admin@saasforge.test",password:$password,contextType:"PLATFORM"}')"
platform_token="$(jq -r '.accessToken' "$response_body")"
runtime_create_key="$(uuid_v7)"
request 201 POST /api/v1/platform/oauth-clients \
  '{"displayName":"issue-86-platform-mechanism","allowedScopes":["runtime:read","runtime:quota:write"]}' \
  "$platform_token" "$runtime_create_key"
runtime_client="$(jq -r '.clientId' "$response_body")"
runtime_secret="$(jq -r '.clientSecret' "$response_body")"
token_request 200 "$runtime_client" "$runtime_secret" 'runtime:quota:write'
insufficient_token="$(jq -r '.access_token' "$response_body")"
token_request 200 "$runtime_client" "$runtime_secret" 'runtime:quota:write runtime:read'
runtime_token="$(jq -r '.access_token' "$response_body")"
request 200 POST /__test/platform-mechanism '' "$runtime_token"
assert_json '.clientId == $client and .scopes == ["runtime:quota:write","runtime:read"] and
  (has("accessToken") | not) and (has("token") | not)' --arg client "$runtime_client"

echo "[6/9] 验证 Scope、Token 类型、Ready 与保留 Header 错误矩阵"
request 403 POST /__test/platform-mechanism '' "$insufficient_token"
assert_json '.code == "ACCESS_TOKEN_SCOPE_INSUFFICIENT"'
request 401 POST /__test/platform-mechanism '' "$platform_token"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
request 200 POST /__test/platform-mechanism '' "$runtime_token" '' \
  --header 'x-client: forged'
receiver_port="$(compose port --index 1 platform-mechanism-receiver 8080 | sed 's/.*://')"
direct_receiver_request 400 "$receiver_port" "$runtime_token" --header 'X-Tenant-Context: forged'
assert_json '.code == "UNTRUSTED_CONTEXT_HEADER"'
compose exec -T redis sh -eu -c '
  redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SET \
    sf:dev:iam-service:revocation-index-ready:v1:state 0 >/dev/null
'
request 503 POST /__test/platform-mechanism '' "$runtime_token"
assert_json '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"'
direct_receiver_request 503 "$receiver_port" "$runtime_token"
assert_json '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"'
compose exec -T redis sh -eu -c '
  redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SET \
    sf:dev:iam-service:revocation-index-ready:v1:state 1 >/dev/null
'

echo "[7/9] 验证 Nacos 扩缩容、实例摘除、故障切换和无健康实例 503"
instance_ids="$work_directory/instance-ids.txt"
for _ in $(seq 1 30); do
  request 200 POST /__test/platform-mechanism '' "$runtime_token"
  jq -r '.instanceId' "$response_body" >>"$instance_ids"
done
[[ "$(sort -u "$instance_ids" | wc -l | tr -d ' ')" -eq 2 ]]
first_container="$(compose ps -q platform-mechanism-receiver | head -n 1)"
docker stop "$first_container" >/dev/null
for _ in $(seq 1 60); do
  if request 200 POST /__test/platform-mechanism '' "$runtime_token" 2>/dev/null; then
    break
  fi
  sleep 1
done
compose stop platform-mechanism-receiver >/dev/null
for _ in $(seq 1 60); do
  if request 503 POST /__test/platform-mechanism '' "$runtime_token" 2>/dev/null \
      && jq --exit-status '.code == "UPSTREAM_UNAVAILABLE"' "$response_body" >/dev/null; then
    break
  fi
  sleep 1
done
assert_json '.code == "UPSTREAM_UNAVAILABLE"'
compose up --detach --scale platform-mechanism-receiver=1 platform-mechanism-receiver >/dev/null
wait_for_started_instances platform-mechanism-receiver 1
for _ in $(seq 1 60); do
  request 200 POST /__test/platform-mechanism '' "$runtime_token" 2>/dev/null && break
  sleep 1
done
request 404 GET /__test/registered-only-audit-service
assert_json '.code == "ROUTE_NOT_FOUND"'

echo "[8/9] 验证 Client 吊销与现有 User、Cookie、Basic、404、405、Allow、Trace 回归"
revoke_key="$(uuid_v7)"
request 204 POST "/api/v1/platform/oauth-clients/$runtime_client/revocations" '' \
  "$platform_token" "$revoke_key"
request 401 POST /__test/platform-mechanism '' "$runtime_token"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
receiver_port="$(compose port --index 1 platform-mechanism-receiver 8080 | sed 's/.*://')"
direct_receiver_request 401 "$receiver_port" "$runtime_token"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
token_request 401 "$runtime_client" "$runtime_secret" 'runtime:read'
assert_json '.code == "CLIENT_CREDENTIALS_INVALID"'
request 200 GET "/api/v1/platform/oauth-clients/$runtime_client" '' "$platform_token"
request 404 GET /__test/not-declared
assert_json '.code == "ROUTE_NOT_FOUND"'
request 405 GET /__test/platform-mechanism
assert_json '.code == "METHOD_NOT_ALLOWED"'
tr -d '\r' <"$response_headers" | grep -Eiq '^Allow:.*POST'
traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'
request 401 POST /__test/platform-mechanism '' "$runtime_token" '' --header "traceparent: $traceparent"
assert_json '.traceId == "4bf92f3577b34da6a3ce929d0e0e4736"'

echo "[9/9] 扫描 Catalog、响应和应用日志，确认不泄露原 Token 或 Client Secret"
compose logs --no-color gateway iam-service platform-mechanism-receiver >"$work_directory/application.log"
if grep -F -- "$runtime_token" "$work_directory/application.log" "$response_body" >/dev/null \
    || grep -F -- "$runtime_secret" "$work_directory/application.log" "$response_body" >/dev/null; then
  echo "平台机制验收输出泄露 Token 或 Client Secret" >&2
  exit 1
fi

echo "Issue #86 平台机制 Compose 验收通过。"
echo "证据边界：真实 IAM、PostgreSQL、Redis、Nacos、Gateway 与 Starter 接收端的平台机制验收；不代表生产 Runtime 业务闭环。"

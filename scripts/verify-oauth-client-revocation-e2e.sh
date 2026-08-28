#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"
readonly override_file="$compose_directory/oauth-client-revocation-e2e.override.yaml"
readonly project_name="saas-forge-oauth-client-$PPID-$$"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"
readonly environment_file="$work_directory/compose.env"
readonly response_body="$work_directory/response.json"
readonly response_headers="$work_directory/response.headers"
readonly cookie_jar="$work_directory/cookies.txt"
readonly compose_log="$work_directory/compose.log"
readonly probe_log="$work_directory/probe.log"

gateway_base=""
iam_grpc_port=""

compose() {
  docker compose --ansi never \
    --project-directory "$compose_directory" \
    --env-file "$environment_file" \
    --project-name "$project_name" \
    --file "$compose_directory/compose.yaml" \
    --file "$override_file" \
    "$@"
}

cleanup() {
  local exit_code="$?"
  compose logs --no-color >"$compose_log" 2>/dev/null || true
  if [[ "$exit_code" -ne 0 ]]; then
    compose ps --all >&2 || true
    compose logs --no-color --tail 120 \
      gateway iam-service tenant-access-service entitlement-service 2>/dev/null \
      | sed -E 's/((authorization|accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work_directory"
  return "$exit_code"
}

on_error() {
  local exit_code="$?"
  printf '验收失败：line=%s command=%s exit=%s\n' \
    "${BASH_LINENO[0]}" "$BASH_COMMAND" "$exit_code" >&2
  return "$exit_code"
}

on_interrupt() {
  trap - EXIT
  cleanup
  exit 130
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
    printf 'Expected exactly one runtime JAR for %s, found %s\n' \
      "$module" "${#runtime_jars[@]}" >&2
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
    printf 'Bootstrap service failed: %s\n' "$service" >&2
    sed -E 's/((authorization|accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' \
      "$log_file" | tail -n 120 >&2
    return 1
  fi
}

grant_container_secret_access() {
  local -a secret_files=("$secret_directory"/*)
  chmod 0640 "${secret_files[@]}"
}

wait_for_service_started() {
  local service="$1"
  for _ in $(seq 1 180); do
    if compose logs --no-color "$service" 2>/dev/null | grep 'Started .*Application' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "服务 $service 未在预期时间内启动" >&2
  return 1
}

start_service() {
  local service="$1"
  compose up --detach "$service" >/dev/null
  wait_for_service_started "$service"
}

wait_for_gateway() {
  local status
  for _ in $(seq 1 180); do
    status="$(curl --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' \
      "$gateway_base/.well-known/jwks.json" || true)"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Gateway 未在预期时间内 Ready" >&2
  return 1
}

wait_for_redis_revocation_ready() {
  local ready
  for _ in $(seq 1 90); do
    ready="$(compose exec -T redis sh -eu -c '
      redis-cli --no-auth-warning -a "$REDIS_PASSWORD" GET \
        sf:dev:iam-service:revocation-index-ready:v1:state
    ' 2>/dev/null | tr -d '\r' || true)"
    if [[ "$ready" == "1" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "IAM Revocation Index 未在预期时间内恢复 Ready" >&2
  return 1
}

request() {
  local expected_status="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local bearer="${5:-}"
  local idempotency_key="${6:-}"
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
  status="$(curl "${arguments[@]}" "$gateway_base$path")"
  if [[ "$status" != "$expected_status" ]]; then
    echo "请求 $method $path 预期 HTTP $expected_status，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

token_request() {
  local expected_status="$1"
  local client_id="$2"
  local client_secret="$3"
  local scope="$4"
  local status
  status="$(curl --silent --show-error \
    --connect-timeout 2 --max-time 30 \
    --dump-header "$response_headers" --output "$response_body" --write-out '%{http_code}' \
    --user "$client_id:$client_secret" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "scope=$scope" \
    "$gateway_base/oauth2/token")"
  if [[ "$status" != "$expected_status" ]]; then
    echo "Client Credentials 预期 HTTP $expected_status，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

token_request_fails_closed_during_redis_outage() {
  local client_id="$1"
  local client_secret="$2"
  local scope="$3"
  local status curl_exit
  rm -f "$response_body" "$response_headers"
  if status="$(curl --silent --show-error \
      --connect-timeout 2 --max-time 30 \
      --dump-header "$response_headers" --output "$response_body" --write-out '%{http_code}' \
      --user "$client_id:$client_secret" \
      --header 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=client_credentials' \
      --data-urlencode "scope=$scope" \
      "$gateway_base/oauth2/token")"; then
    curl_exit=0
  else
    curl_exit="$?"
  fi

  if [[ "$curl_exit" -eq 0 ]]; then
    if [[ "$status" != "503" ]] || ! jq --exit-status \
      '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE" and (has("access_token") | not)' \
      "$response_body" >/dev/null; then
      echo "Redis 停机时签发端未按 503 失败关闭" >&2
      return 1
    fi
    cat "$response_body" >>"$problem_log"
    return 0
  fi

  if [[ "$curl_exit" -eq 28 ]] && { [[ ! -s "$response_body" ]] \
    || jq --exit-status 'has("access_token") | not' "$response_body" >/dev/null; }; then
    echo "Redis 停机时签发端在 30 秒内未返回 Token，保持失败关闭"
    return 0
  fi
  echo "Redis 停机时签发端出现非预期结果：curl=$curl_exit http=$status" >&2
  return 1
}

assert_json() {
  local expression="$1"
  shift
  if ! jq --exit-status "$@" "$expression" "$response_body" >/dev/null; then
    echo "JSON 断言失败：$expression" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

decode_jwt_claims() {
  local token="$1"
  local payload
  payload="$(printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+')"
  case $((${#payload} % 4)) in
    2) payload="${payload}==" ;;
    3) payload="${payload}=" ;;
  esac
  printf '%s' "$payload" | openssl base64 -d -A
}

assert_service_claims() {
  local token="$1"
  local expected_client="$2"
  local expected_scope="$3"
  decode_jwt_claims "$token" | jq --exit-status \
    --arg client "$expected_client" --arg scope "$expected_scope" '
      (. | keys | sort) == ["aud","client_id","exp","iat","iss","jti","scope","sub"] and
      .client_id == $client and .sub == $client and .scope == $scope and
      (.exp > now) and
      (has("identityId") | not) and (has("membershipId") | not) and
      (has("tenantId") | not) and (has("roles") | not) and (has("permissions") | not)
    ' >/dev/null
}

probe_receiver() {
  local token="$1"
  local identity_id="$2"
  local expectation="$3"
  if ! SERVICE_ACCESS_TOKEN="$token" "$repository_root/mvnw" --quiet \
      -pl contracts/protobuf \
      -Denforcer.skip=true \
      org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
      -Dexec.mainClass=io.saasforge.contracts.acceptance.PlatformAuthorizationGrpcProbe \
      -Dexec.classpathScope=test \
      -Dexec.args="127.0.0.1 $iam_grpc_port $identity_id $expectation" \
      >>"$probe_log" 2>&1; then
    sed -E 's/((authorization|accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' \
      "$probe_log" >&2
    return 1
  fi
  grep -E 'IAM Service Token receiver (accepted|rejected)' "$probe_log" | tail -n 1
}

postgres_assert() {
  local query="$1"
  compose exec -T postgres sh -eu -c '
    psql --username "$POSTGRES_USER" --dbname iam_db --tuples-only --no-align --command "$1"
  ' sh "$query" | grep -qx t
}

assert_sensitive_material_absent() {
  local database_dump="$work_directory/database-dump.txt"
  local application_log="$work_directory/application.log"
  local event_dump="$work_directory/events.jsonl"
  local test_output="$work_directory/test-output.txt"
  local value
  compose exec -T postgres sh -eu -c '
    pg_dump --username "$POSTGRES_USER" --data-only --inserts iam_db
  ' >"$database_dump"
  compose exec -T postgres sh -eu -c '
    psql --username "$POSTGRES_USER" --dbname iam_db --tuples-only --no-align \
      --command "SELECT event_snapshot::text FROM iam_outbox_events"
  ' >"$event_dump"
  compose logs --no-color gateway iam-service tenant-access-service entitlement-service \
    >"$application_log"
  cat "$work_directory"/*.log >"$test_output"

  for value in \
    "$initial_password" "$platform_password" "$runtime_secret" "$rotated_secret" \
    "$replacement_secret" "$platform_token" "$runtime_token" "$service_token" \
    "$(<"$secret_directory/iam-client-secret")" \
    "$(<"$secret_directory/tenant-access-client-secret")" \
    "$(<"$secret_directory/entitlement-client-secret")"; do
    if grep -F -- "$value" \
      "$database_dump" "$event_dump" "$application_log" "$problem_log" "$test_output" >/dev/null; then
      echo "数据库、事件、日志、Problem 或测试输出中发现敏感材料" >&2
      return 1
    fi
  done

  if jq --exit-status '
      .. | objects | keys[] | ascii_downcase |
      test("(^|_)(authorization|token|secret|digest)(_|$)")
    ' "$event_dump" >/dev/null 2>&1; then
    echo "OAuth Client 事件中发现凭据字段" >&2
    return 1
  fi
}

trap on_error ERR
trap cleanup EXIT
trap on_interrupt INT TERM

for command in docker curl jq ruby openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "缺少验收依赖命令: $command" >&2
    exit 1
  }
done

write_environment
touch "$cookie_jar"
problem_log="$work_directory/problems.jsonl"
touch "$problem_log"

echo "[1/10] 校验隔离 Compose、随机端口与显式引导任务"
compose_config="$work_directory/compose-config.json"
compose --profile bootstrap --profile service-client-bootstrap config --format json >"$compose_config"
if ! jq --exit-status '
  .services["iam-platform-admin-bootstrap"].profiles == ["bootstrap"] and
  .services["iam-reserved-service-client-bootstrap"].profiles == ["service-client-bootstrap"] and
  ([.services["iam-service"].ports[] |
      select(.target == 9090 and .host_ip == "127.0.0.1")] | length == 1) and
  ([.services.gateway.ports[] |
      select(.target == 8080 and .host_ip == "127.0.0.1")] | length == 1)
' "$compose_config" >/dev/null; then
  jq '{bootstrap:.services["iam-platform-admin-bootstrap"].profiles,
       serviceClientBootstrap:.services["iam-reserved-service-client-bootstrap"].profiles,
       iamPorts:.services["iam-service"].ports,
       gatewayPorts:.services.gateway.ports}' "$compose_config" >&2
  exit 1
fi

echo "[2/10] 在全新 PostgreSQL 数据卷迁移并初始化 IAM Signing Key"
COMPOSE_PROJECT_NAME="$project_name" \
LOCAL_COMPOSE_ENV_FILE="$environment_file" \
LOCAL_COMPOSE_OVERRIDE_FILE="$override_file" \
  bash "$repository_root/scripts/initialize-local-iam-signing-key.sh" >/dev/null
grant_container_secret_access

echo "[3/10] 构建制品并显式引导 Platform Admin 与 Reserved Client"
"$repository_root/mvnw" --batch-mode --no-transfer-progress \
  -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service,services/audit-service,contracts/protobuf \
  -am package -DskipTests >"$work_directory/maven-package.log"
build_runtime_image gateway saasforge/gateway:local
build_runtime_image services/iam-service saasforge/iam-service:local
build_runtime_image services/tenant-access-service saasforge/tenant-access-service:local
build_runtime_image services/entitlement-service saasforge/entitlement-service:local
build_runtime_image services/audit-service saasforge/audit-service:local
run_bootstrap bootstrap iam-platform-admin-bootstrap
run_bootstrap service-client-bootstrap iam-reserved-service-client-bootstrap

echo "[4/10] 启动真实 Gateway、IAM、PostgreSQL、Redis 与接收端"
for service in iam-service tenant-access-service entitlement-service audit-service gateway; do
  start_service "$service"
done
gateway_port="$(compose port gateway 8080 | sed 's/.*://')"
iam_grpc_port="$(compose port iam-service 9090 | sed 's/.*://')"
gateway_base="http://127.0.0.1:$gateway_port"
wait_for_gateway
wait_for_redis_revocation_ready
"$repository_root/mvnw" --quiet -pl contracts/protobuf -am test-compile

echo "[5/10] 完成 Platform Admin 登录并经 Gateway 创建 Runtime Client"
initial_password="$(<"$secret_directory/platform-admin-password")"
request 200 POST /api/v1/auth/login \
  "$(jq -cn --arg password "$initial_password" \
    '{email:"platform-admin@saasforge.test",password:$password,contextType:"PLATFORM"}')"
assert_json '.contextState == "PASSWORD_CHANGE_REQUIRED" and (has("accessToken") | not)'
platform_password="Platform-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-changes \
  "$(jq -cn --arg password "$platform_password" '{newPassword:$password}')"
request 200 POST /api/v1/auth/login \
  "$(jq -cn --arg password "$platform_password" \
    '{email:"platform-admin@saasforge.test",password:$password,contextType:"PLATFORM"}')"
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
platform_token="$(jq -r '.accessToken' "$response_body")"
platform_identity="$(decode_jwt_claims "$platform_token" | jq -r '.identityId')"

runtime_create_key="$(uuid_v7)"
runtime_request='{"displayName":"issue-74-runtime","allowedScopes":["runtime:read"]}'
request 201 POST /api/v1/platform/oauth-clients "$runtime_request" \
  "$platform_token" "$runtime_create_key"
tr -d '\r' <"$response_headers" | grep -Fiqx 'Cache-Control: no-store'
runtime_client="$(jq -r '.clientId' "$response_body")"
runtime_secret="$(jq -r '.clientSecret' "$response_body")"
[[ "${#runtime_secret}" -eq 43 ]]
runtime_location="$(tr -d '\r' <"$response_headers" | sed -n 's/^Location: //Ip' | tail -n 1)"
[[ "$runtime_location" == "/api/v1/platform/oauth-clients/$runtime_client" ]]
request 200 GET "$runtime_location" '' "$platform_token"
assert_json '.clientId == $client and .clientType == "RUNTIME_SERVICE" and
  .allowedScopes == ["runtime:read"] and .status == "ACTIVE" and
  (has("clientSecret") | not) and (has("secretId") | not) and (has("digest") | not)' \
  --arg client "$runtime_client"
if grep -Eiq 'secret|digest' "$response_body"; then
  echo "Client 详情泄露 Secret 元数据" >&2
  exit 1
fi
request 409 POST /api/v1/platform/oauth-clients "$runtime_request" \
  "$platform_token" "$runtime_create_key"
assert_json '.code == "CLIENT_SECRET_ALREADY_REVEALED" and .clientId == $client' \
  --arg client "$runtime_client"
cat "$response_body" >>"$problem_log"

echo "[6/10] 真实 IAM 签发 Token，并直接断言 Claim 白名单"
token_request 200 "$runtime_client" "$runtime_secret" 'runtime:read'
runtime_token="$(jq -r '.access_token' "$response_body")"
assert_service_claims "$runtime_token" "$runtime_client" 'runtime:read'

echo "[7/10] 经 Gateway 轮换并恢复 Reserved Client，真实接收端接受 Token"
tenant_access_client="$(<"$secret_directory/tenant-access-client-id")"
stable_secret="$(<"$secret_directory/tenant-access-client-secret")"
request 200 GET "/api/v1/platform/oauth-clients/$tenant_access_client" '' "$platform_token"
assert_json '.clientType == "RESERVED_SERVICE" and .reservedServiceKey == "TENANT_ACCESS" and
  (.allowedScopes | index("iam:platform-role:read") != null)'
rotation_key="$(uuid_v7)"
request 200 POST "/api/v1/platform/oauth-clients/$tenant_access_client/secret-rotations" '' \
  "$platform_token" "$rotation_key"
tr -d '\r' <"$response_headers" | grep -Fiqx 'Cache-Control: no-store'
rotated_secret="$(jq -r '.clientSecret' "$response_body")"
recovery_key="$(uuid_v7)"
request 200 POST "/api/v1/platform/oauth-clients/$tenant_access_client/secret-issuance-recoveries" \
  "$(jq -cn --arg key "$rotation_key" '{originalIdempotencyKey:$key}')" \
  "$platform_token" "$recovery_key"
tr -d '\r' <"$response_headers" | grep -Fiqx 'Cache-Control: no-store'
replacement_secret="$(jq -r '.clientSecret' "$response_body")"
token_request 401 "$tenant_access_client" "$rotated_secret" 'iam:platform-role:read'
assert_json '.code == "CLIENT_CREDENTIALS_INVALID"'
cat "$response_body" >>"$problem_log"
token_request 200 "$tenant_access_client" "$replacement_secret" 'iam:platform-role:read'
service_token="$(jq -r '.access_token' "$response_body")"
assert_service_claims "$service_token" "$tenant_access_client" 'iam:platform-role:read'
probe_receiver "$service_token" "$platform_identity" allowed

echo "[8/10] Ready=false 与 Redis 停机时签发方、真实接收端同时失败关闭"
compose exec -T redis sh -eu -c '
  redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SET \
    sf:dev:iam-service:revocation-index-ready:v1:state 0 >/dev/null
'
token_request 503 "$runtime_client" "$runtime_secret" 'runtime:read'
assert_json '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"'
cat "$response_body" >>"$problem_log"
probe_receiver "$service_token" "$platform_identity" rejected
compose exec -T redis sh -eu -c '
  redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SET \
    sf:dev:iam-service:revocation-index-ready:v1:state 1 >/dev/null
'
token_request 200 "$runtime_client" "$runtime_secret" 'runtime:read'
probe_receiver "$service_token" "$platform_identity" allowed

compose stop redis >/dev/null
token_request_fails_closed_during_redis_outage \
  "$runtime_client" "$runtime_secret" 'runtime:read'
probe_receiver "$service_token" "$platform_identity" unavailable
compose start redis >/dev/null
wait_for_redis_revocation_ready
token_request 200 "$runtime_client" "$runtime_secret" 'runtime:read'
probe_receiver "$service_token" "$platform_identity" allowed

echo "[9/10] 整 Client 吊销后同一枚未过期 Token 被真实接收端立即拒绝"
revoke_key="$(uuid_v7)"
request 204 POST "/api/v1/platform/oauth-clients/$tenant_access_client/revocations" '' \
  "$platform_token" "$revoke_key"
decode_jwt_claims "$service_token" | jq --exit-status '.exp > now' >/dev/null
probe_receiver "$service_token" "$platform_identity" rejected
token_request 401 "$tenant_access_client" "$replacement_secret" 'iam:platform-role:read'
assert_json '.code == "CLIENT_CREDENTIALS_INVALID"'
cat "$response_body" >>"$problem_log"
postgres_assert "SELECT
  (SELECT count(*) = 1 FROM iam_outbox_events
     WHERE ordering_key = '$runtime_client'
       AND event_snapshot->>'type' = 'com.saasforge.iam.oauth-client.created.v1')
  AND
  (SELECT count(*) = 3 FROM iam_outbox_events
     WHERE ordering_key = '$tenant_access_client'
       AND event_snapshot->>'type' IN (
         'com.saasforge.iam.client-secret.rotated.v1',
         'com.saasforge.iam.client-secret.issuance-recovered.v1',
         'com.saasforge.iam.oauth-client.revoked.v1'))
  AND
  (SELECT count(*) = 4 FROM iam_outbox_events
     WHERE ordering_key IN ('$runtime_client', '$tenant_access_client')
       AND event_snapshot->'data'->>'actorType' = 'IDENTITY'
       AND event_snapshot->'data'->>'actorIdentityId' = '$platform_identity'
       AND NOT (event_snapshot->'data' ? 'deploymentOperationId'))"

echo "[10/10] 扫描数据库、事件、日志、Problem、Trace 与测试输出的敏感材料"
assert_sensitive_material_absent

echo "OAuth Client 即时吊销 Compose E2E 验收通过。"
echo "已证明真实 Gateway 管理、IAM 签发、PostgreSQL/Redis、Claim 白名单、真实 IAM gRPC 接收、Ready/Redis fail-closed 与同一未过期 Token 的即时拒绝。"

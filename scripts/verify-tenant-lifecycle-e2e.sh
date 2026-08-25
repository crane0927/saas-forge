#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"
readonly override_file="$compose_directory/tenant-lifecycle-e2e.override.yaml"
readonly project_name="saas-forge-tenant-lifecycle-$PPID-$$"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"
readonly environment_file="$work_directory/compose.env"
readonly response_body="$work_directory/response.json"
readonly response_headers="$work_directory/response.headers"
readonly cookie_jar="$work_directory/cookies.txt"
readonly compose_log="$work_directory/compose.log"

gateway_base=""
mailpit_base=""

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
    compose logs --no-color --tail 100 \
      gateway iam-service tenant-access-service entitlement-service audit-service 2>/dev/null \
      | sed -E 's/(accessToken=)[^,}]*/\1[REDACTED]/g' >&2 || true
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
    if [[ "${#runtime_jars[@]}" -gt 0 ]]; then
      printf '  %s\n' "${runtime_jars[@]}" >&2
    fi
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
    tail -n 120 "$log_file" \
      | sed -E 's/((accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' >&2
    return 1
  fi
}

grant_container_secret_access() {
  local -a secret_files=("$secret_directory"/*)
  # Linux bind mount 保留宿主权限；仅向容器补充的宿主主组开放读取，避免改用 root 或全局可读。
  chmod 0640 "${secret_files[@]}"
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

wait_for_service_started() {
  local service="$1"
  for _ in $(seq 1 180); do
    if compose logs --no-color "$service" 2>/dev/null | grep 'Started .*Application' >/dev/null; then
      return 0
    fi
    if [[ "$(compose ps --all --format json "$service" | jq -r 'select(.Service == $service) | .State' --arg service "$service")" == "exited" ]]; then
      echo "服务 $service 启动期间已退出" >&2
      return 1
    fi
    sleep 2
  done
  echo "服务 $service 未在预期时间内启动" >&2
  return 1
}

start_service() {
  local service="$1"
  local attempt
  for attempt in 1 2 3; do
    compose up --detach "$service" >/dev/null
    if wait_for_service_started "$service"; then
      return 0
    fi
    echo "服务 $service 第 $attempt 次启动失败，等待基础设施稳定后重试" >&2
    sleep 5
  done
  echo "服务 $service 连续三次启动失败" >&2
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
  local arguments=(
    --silent --show-error
    --request "$method"
    --dump-header "$response_headers"
    --output "$response_body"
    --write-out '%{http_code}'
    --header 'Content-Type: application/json'
    --header 'X-SF-CSRF: 1'
    --header 'Origin: https://console.saasforge.test'
    --header 'Sec-Fetch-Site: same-site'
    --cookie "$cookie_jar"
    --cookie-jar "$cookie_jar"
  )
  if [[ -n "$body" ]]; then
    arguments+=(--data-binary "$body")
  fi
  if [[ -n "$bearer" ]]; then
    arguments+=(--header "Authorization: Bearer $bearer")
  fi
  if [[ -n "$idempotency_key" ]]; then
    arguments+=(--header "Idempotency-Key: $idempotency_key")
  fi
  status="$(curl "${arguments[@]}" "$gateway_base$path")"
  if [[ "$status" != "$expected_status" ]]; then
    echo "请求 $method $path 预期 HTTP ${expected_status}，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

assert_json() {
  local expression="$1"
  if ! jq --exit-status "$expression" "$response_body" >/dev/null; then
    echo "JSON 断言失败：$expression" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

login() {
  local email="$1"
  local password="$2"
  local context_type="$3"
  request 200 POST /api/v1/auth/login \
    "$(jq -cn --arg email "$email" --arg password "$password" --arg context "$context_type" \
      '{email:$email,password:$password,contextType:$context}')"
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

wait_for_mail() {
  local recipient="$1"
  local messages message_id message
  for _ in $(seq 1 60); do
    messages="$(curl --silent --show-error "$mailpit_base/api/v1/messages")"
    message_id="$(printf '%s' "$messages" | jq -r --arg recipient "$recipient" \
      '.messages[]? | select(any(.To[]?; .Address == $recipient)) | .ID' | head -n 1)"
    if [[ -n "$message_id" ]]; then
      message="$(curl --silent --show-error "$mailpit_base/api/v1/message/$message_id")"
      printf '%s' "$message"
      return 0
    fi
    sleep 1
  done
  echo "Mailpit 未收到 Tenant Admin Password Setup 邮件" >&2
  return 1
}

assert_sensitive_material_absent() {
  local dump_file="$work_directory/database-dump.txt"
  local application_log="$work_directory/application.log"
  local value
  compose exec -T postgres sh -eu -c '
    for database in iam_db tenant_access_db entitlement_db audit_db; do
      pg_dump --username "$POSTGRES_USER" --data-only --inserts "$database"
    done
  ' >"$dump_file"
  compose logs --no-color iam-service tenant-access-service entitlement-service gateway \
    >"$application_log"

  for value in \
    "$initial_password" "$platform_password" "$tenant_password" "$password_setup_token" \
    "$(<"$secret_directory/iam-client-secret")" \
    "$(<"$secret_directory/tenant-access-client-secret")" \
    "$(<"$secret_directory/entitlement-client-secret")"; do
    if grep -F -- "$value" "$dump_file" "$application_log" "$business_response_log" >/dev/null; then
      echo "数据库、应用日志或业务 API 响应中发现敏感明文" >&2
      return 1
    fi
  done
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
business_response_log="$work_directory/business-responses.jsonl"
touch "$business_response_log"

echo "[1/10] 校验隔离 Compose、挂载 Secret 与显式引导任务"
compose config --quiet
compose --profile bootstrap --profile service-client-bootstrap config --format json | jq --exit-status '
  .services["iam-platform-admin-bootstrap"].profiles == ["bootstrap"] and
  .services["iam-reserved-service-client-bootstrap"].profiles == ["service-client-bootstrap"] and
  ([.services["iam-platform-admin-bootstrap"], .services["iam-reserved-service-client-bootstrap"],
    .services["iam-service"], .services["tenant-access-service"], .services["entitlement-service"]]
    | all(.group_add | any(test("^[0-9]+$")))) and
  ([.services["iam-reserved-service-client-bootstrap"].volumes[] | select(.read_only == true)] | length == 6) and
  ([.services["iam-service"].volumes[], .services["tenant-access-service"].volumes[],
    .services["entitlement-service"].volumes[] | select(.read_only == true)] | length >= 7) and
  (.services.mailpit.image | startswith("axllent/mailpit:"))
' >/dev/null

echo "[2/10] 在全新 PostgreSQL 数据卷迁移并初始化 IAM Signing Key"
COMPOSE_PROJECT_NAME="$project_name" \
LOCAL_COMPOSE_ENV_FILE="$environment_file" \
LOCAL_COMPOSE_OVERRIDE_FILE="$override_file" \
  bash "$repository_root/scripts/initialize-local-iam-signing-key.sh" >/dev/null
grant_container_secret_access

echo "[3/10] 显式引导 Platform Admin 与三个保留服务 Client"
"$repository_root/mvnw" --batch-mode --no-transfer-progress \
  -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service,services/audit-service \
  -am package -DskipTests >"$work_directory/maven-package.log"
build_runtime_image gateway saasforge/gateway:local
build_runtime_image services/iam-service saasforge/iam-service:local
build_runtime_image services/tenant-access-service saasforge/tenant-access-service:local
build_runtime_image services/entitlement-service saasforge/entitlement-service:local
build_runtime_image services/audit-service saasforge/audit-service:local
run_bootstrap bootstrap iam-platform-admin-bootstrap
run_bootstrap service-client-bootstrap iam-reserved-service-client-bootstrap
initial_password="$(<"$secret_directory/platform-admin-password")"
conflicting_password="$(openssl rand -base64 32 | tr -d '\n')"
printf '%s\n' "$conflicting_password" >"$secret_directory/platform-admin-password-conflict"
grant_container_secret_access
if IAM_PLATFORM_ADMIN_PASSWORD_FILE="$secret_directory/platform-admin-password-conflict" \
  compose --profile bootstrap run --rm iam-platform-admin-bootstrap >/dev/null 2>&1; then
  echo "不同 Platform Admin 初始凭证不应被引导任务覆盖" >&2
  exit 1
fi
unset conflicting_password

echo "[4/10] 启动真实拓扑并完成 Platform Admin 首次改密"
for service in iam-service tenant-access-service entitlement-service audit-service gateway; do
  start_service "$service"
done
gateway_port="$(compose port gateway 8080 | sed 's/.*://')"
mailpit_port="$(compose port mailpit 8025 | sed 's/.*://')"
gateway_base="http://127.0.0.1:$gateway_port"
mailpit_base="http://127.0.0.1:$mailpit_port"
wait_for_gateway
login 'platform-admin@saasforge.test' "$initial_password" PLATFORM
assert_json '.contextState == "PASSWORD_CHANGE_REQUIRED" and (has("accessToken") | not)'
platform_password="Platform-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-changes \
  "$(jq -cn --arg password "$platform_password" '{newPassword:$password}')"
login 'platform-admin@saasforge.test' "$platform_password" PLATFORM
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
platform_token="$(jq -r '.accessToken' "$response_body")"

echo "[5/10] 通过平台 API 创建并激活 max_users Quota Definition 与 Plan"
request 201 POST /api/v1/platform/quota-definitions '{"code":"max_users"}' \
  "$platform_token" "$(uuid_v7)"
quota_definition_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/quota-definitions/$quota_definition_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"
request 201 POST /api/v1/platform/plans \
  "$(jq -cn --arg id "$quota_definition_id" \
    '{code:"tenant-e2e",displayName:"Tenant E2E",quotaLimits:[{quotaDefinitionId:$id,limit:1}]}')" \
  "$platform_token" "$(uuid_v7)"
plan_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/plans/$plan_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"

echo "[6/10] 创建 PENDING Tenant、首个 ACTIVE Subscription 并初始化管理员"
tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 86400).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$tenant_expires_at" \
    '{displayName:"Tenant E2E",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
tenant_id="$(jq -r '.id' "$response_body")"
assert_json '.status == "PENDING"'
cat "$response_body" >>"$business_response_log"
request 201 POST "/api/v1/platform/tenants/$tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
assert_json '.status == "ACTIVE"'
cat "$response_body" >>"$business_response_log"
tenant_admin_email='tenant-admin@saasforge.test'
request 200 POST "/api/v1/platform/tenants/$tenant_id/administrator-initializations" \
  "$(jq -cn --arg email "$tenant_admin_email" \
    '{administratorEmail:$email,administratorDisplayName:"Tenant Admin"}')" \
  "$platform_token" "$(uuid_v7)"
assert_json '.status == "ACTIVE"'
cat "$response_body" >>"$business_response_log"

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --set=tenant_id="'"$tenant_id"'" <<SQL | grep -qx t
      SELECT (tenant.tenant_status = '\''ACTIVE'\'')
         AND (membership.membership_status = '\''ENABLED'\'')
         AND (role.role_key = '\''TENANT_ADMINISTRATOR'\'' AND role.system_managed)
         AND (assignment.assigned_at IS NOT NULL)
         AND (initial.membership_id = membership.id)
      FROM tenants tenant
      JOIN memberships membership ON membership.tenant_id = tenant.id
      JOIN membership_role_assignments assignment ON assignment.membership_id = membership.id
      JOIN tenant_roles role ON role.id = assignment.role_id
      JOIN initial_tenant_administrators initial ON initial.tenant_id = tenant.id
      WHERE tenant.id = :'"'"'tenant_id'"'"';
SQL
' >/dev/null

echo "[7/10] 从 Mailpit Fragment 链接完成 Password Setup 并验证 Tenant Context"
mail_message="$(wait_for_mail "$tenant_admin_email")"
password_setup_link="$(printf '%s' "$mail_message" | ruby -rjson -rcgi -e '
  message = JSON.parse(STDIN.read)
  body = [message["HTML"], message["Text"]].compact.join("\n")
  link = CGI.unescapeHTML(body)[%r{https://console\.saasforge\.test/password-setup#token=[A-Za-z0-9_-]+}]
  abort "Password Setup 邮件缺少固定 HTTPS Fragment 链接" unless link
  puts link
')"
password_setup_token="${password_setup_link#*#token=}"
curl --silent --show-error --fail --dump-header "$response_headers" "$gateway_base/password-setup" \
  --output "$work_directory/password-setup-page.html"
# curl 按 HTTP CRLF 写入 Header；先标准化换行，避免 BSD grep 与 GNU grep 对 \r 转义解释不同。
tr -d '\r' <"$response_headers" | grep -Fiqx 'Referrer-Policy: no-referrer'
tenant_password="Tenant-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-setups \
  "$(jq -cn --arg token "$password_setup_token" --arg password "$tenant_password" \
    '{token:$token,newPassword:$password}')" '' "$(uuid_v7)"
rm -f "$cookie_jar"
touch "$cookie_jar"
login "$tenant_admin_email" "$tenant_password" TENANT
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
tenant_token="$(jq -r '.accessToken' "$response_body")"
tenant_claims="$(decode_jwt_claims "$tenant_token")"
printf '%s' "$tenant_claims" | jq --exit-status --arg tenantId "$tenant_id" \
  '.tenantId == $tenantId and (.membershipId | length > 0)' >/dev/null

echo "[8/10] 验证无平台角色、错误 Scope 与依赖不可用"
request 403 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$tenant_expires_at" \
    '{displayName:"Unauthorized Tenant",expiresAt:$expiresAt}')" \
  "$tenant_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"
iam_client_id="$(<"$secret_directory/iam-client-id")"
iam_client_secret="$(<"$secret_directory/iam-client-secret")"
wrong_scope_status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --user "$iam_client_id:$iam_client_secret" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=iam:identity:write' \
  "$gateway_base/oauth2/token")"
printf '错误 Scope 响应状态：%s\n' "$wrong_scope_status"
[[ "$wrong_scope_status" == "403" ]]
cat "$response_body" >>"$business_response_log"
compose stop iam-service >/dev/null
request 403 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$tenant_expires_at" \
    '{displayName:"Unavailable Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"
assert_json '.code == "PLATFORM_AUTHORIZATION_DENIED"'
compose up --detach iam-service >/dev/null
wait_for_gateway

echo "[9/10] 验证额度耗尽、Tenant 到期与跨 Tenant RLS"
request 201 POST /api/v1/platform/plans \
  "$(jq -cn --arg id "$quota_definition_id" \
    '{code:"tenant-zero",displayName:"Tenant Zero",quotaLimits:[{quotaDefinitionId:$id,limit:0}]}')" \
  "$platform_token" "$(uuid_v7)"
zero_plan_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/plans/$zero_plan_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
zero_tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 3600).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$zero_tenant_expires_at" \
    '{displayName:"Zero Quota Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
zero_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$zero_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$zero_plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
request 409 POST "/api/v1/platform/tenants/$zero_tenant_id/administrator-initializations" \
  '{"administratorEmail":"zero-admin@saasforge.test"}' \
  "$platform_token" "$(uuid_v7)"
assert_json '.code == "QUOTA_EXCEEDED"'
cat "$response_body" >>"$business_response_log"

expired_at="$(ruby -rtime -e 'puts (Time.now.utc + 4).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$expired_at" '{displayName:"Expiring Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
expired_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$expired_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
sleep 5
request 409 POST "/api/v1/platform/tenants/$expired_tenant_id/administrator-initializations" \
  '{"administratorEmail":"expired-admin@saasforge.test"}' \
  "$platform_token" "$(uuid_v7)"
assert_json '.code == "TENANT_EXPIRY_REACHED"'
cat "$response_body" >>"$business_response_log"

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --set=visible="'"$tenant_id"'" --set=hidden="'"$zero_tenant_id"'" <<SQL | grep -qx t
      BEGIN;
      SET LOCAL ROLE tenant_access_app;
      SELECT set_config('\''app.tenant_id'\'', :'"'"'visible'"'"', true);
      SELECT count(*) = 1
      FROM tenants
      WHERE id IN (:'"'"'visible'"'"', :'"'"'hidden'"'"');
      ROLLBACK;
SQL
' >/dev/null

echo "[10/10] 扫描数据库、领域事件、业务 API 响应与应用日志的敏感明文"
assert_sensitive_material_absent

echo "Tenant 生命周期 Compose E2E 验收通过：全新卷、显式引导、平台主链、Tenant 登录及关键负向边界均已验证。"
echo "补偿恢复、凭证冲突、租约接管、Kafka Schema/Trace 与并发 RLS 由对应 PostgreSQL/Kafka Testcontainers 测试覆盖。"

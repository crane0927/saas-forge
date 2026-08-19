#!/usr/bin/env bash
set -euo pipefail

readonly applications=(gateway iam-service tenant-access-service entitlement-service audit-service)

: "${NACOS_SERVER_ADDR:?NACOS_SERVER_ADDR is required}"
: "${NACOS_NAMESPACE:=dev}"
: "${NACOS_IAM_USERNAME:?NACOS_IAM_USERNAME is required}"
: "${NACOS_IAM_PASSWORD:?NACOS_IAM_PASSWORD is required}"
: "${NACOS_TENANT_ACCESS_USERNAME:?NACOS_TENANT_ACCESS_USERNAME is required}"
: "${NACOS_TENANT_ACCESS_PASSWORD:?NACOS_TENANT_ACCESS_PASSWORD is required}"
: "${NACOS_ENTITLEMENT_USERNAME:?NACOS_ENTITLEMENT_USERNAME is required}"
: "${NACOS_ENTITLEMENT_PASSWORD:?NACOS_ENTITLEMENT_PASSWORD is required}"
: "${NACOS_AUDIT_USERNAME:?NACOS_AUDIT_USERNAME is required}"
: "${NACOS_AUDIT_PASSWORD:?NACOS_AUDIT_PASSWORD is required}"
: "${NACOS_GATEWAY_USERNAME:?NACOS_GATEWAY_USERNAME is required}"
: "${NACOS_GATEWAY_PASSWORD:?NACOS_GATEWAY_PASSWORD is required}"

nacos_address="$NACOS_SERVER_ADDR"
case "$nacos_address" in
  http://*|https://*) ;;
  *) nacos_address="http://$nacos_address" ;;
esac
nacos_api="${nacos_address%/}"
case "$nacos_api" in
  */nacos) ;;
  *) nacos_api="$nacos_api/nacos" ;;
esac

response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

login() {
  local username="$1"
  local password="$2"
  local response token
  response="$(curl --fail --silent --show-error --request POST \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password" \
    "$nacos_api/v3/auth/user/login")"
  token="$(printf '%s' "$response" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
  if [[ -z "$token" ]]; then
    echo "Nacos 身份 $username 登录未返回 accessToken" >&2
    exit 1
  fi
  printf '%s' "$token"
}

request_status() {
  local token="$1"
  shift
  curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
    --header "Authorization: Bearer $token" "$@" || true
}

assert_own_config_readable() {
  local application="$1"
  local token="$2"
  local status
  status="$(request_status "$token" --get \
    --data-urlencode "dataId=$application.yaml" \
    --data-urlencode 'groupName=SAAS_FORGE' \
    --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
    "$nacos_api/v3/client/cs/config")"
  if [[ "$status" != "200" ]] || ! grep -Eq '"code"[[:space:]]*:[[:space:]]*0' "$response_file"; then
    echo "$application 工作负载无法读取自己的 Nacos 配置，HTTP $status" >&2
    exit 1
  fi
}

assert_config_access_denied() {
  local application="$1"
  local token="$2"
  local target_application="$3"
  local status

  status="$(request_status "$token" --get \
    --data-urlencode "dataId=$target_application.yaml" \
    --data-urlencode 'groupName=SAAS_FORGE' \
    --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
    "$nacos_api/v3/client/cs/config")"
  if [[ "$status" == "2"* ]] && grep -Eq '"code"[[:space:]]*:[[:space:]]*0' "$response_file"; then
    echo "$application 工作负载不应读取 $target_application 的 Nacos 配置" >&2
    exit 1
  fi

  status="$(request_status "$token" --request POST \
    --data-urlencode "dataId=acl-probe-$application.yaml" \
    --data-urlencode 'groupName=SAAS_FORGE' \
    --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
    --data-urlencode 'type=yaml' \
    --data-urlencode 'content=permission-probe: denied' \
    "$nacos_api/v3/admin/cs/config")"
  if [[ "$status" == "2"* ]] && grep -Eq '"code"[[:space:]]*:[[:space:]]*0' "$response_file"; then
    echo "$application 工作负载不应拥有 Nacos 配置发布权限" >&2
    exit 1
  fi
}

verify_workload() {
  local application="$1"
  local username="$2"
  local password="$3"
  local other_application="$4"
  local token
  token="$(login "$username" "$password")"
  assert_own_config_readable "$application" "$token"
  assert_config_access_denied "$application" "$token" "$other_application"
  echo "已验证 $application 的 Nacos 最小权限"
}

verify_workload iam-service "$NACOS_IAM_USERNAME" "$NACOS_IAM_PASSWORD" gateway
verify_workload tenant-access-service "$NACOS_TENANT_ACCESS_USERNAME" "$NACOS_TENANT_ACCESS_PASSWORD" iam-service
verify_workload entitlement-service "$NACOS_ENTITLEMENT_USERNAME" "$NACOS_ENTITLEMENT_PASSWORD" iam-service
verify_workload audit-service "$NACOS_AUDIT_USERNAME" "$NACOS_AUDIT_PASSWORD" iam-service
verify_workload gateway "$NACOS_GATEWAY_USERNAME" "$NACOS_GATEWAY_PASSWORD" iam-service

#!/bin/sh
set -eu

/bin/sh /scripts/nacos-init.sh

api="http://nacos:8848/nacos"

login() {
  curl --fail --silent --show-error --request POST \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "$api/v3/auth/user/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

bootstrap_token="$(login nacos "$NACOS_BOOTSTRAP_PASSWORD")"
test -n "$bootstrap_token"

if [ "$NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME" = "nacos" ] \
    || [ "$NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME" = "$NACOS_PUBLISH_USERNAME" ]; then
  echo "平台机制测试接收端必须使用独立 Nacos 工作负载身份" >&2
  exit 1
fi

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME" \
  --data-urlencode "password=$NACOS_PLATFORM_MECHANISM_RECEIVER_PASSWORD" \
  "$api/v3/auth/user" >/dev/null || true
curl --fail --silent --show-error --request PUT \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME" \
  --data-urlencode "newPassword=$NACOS_PLATFORM_MECHANISM_RECEIVER_PASSWORD" \
  "$api/v3/auth/user" >/dev/null
curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_PLATFORM_MECHANISM_RECEIVER_USERNAME" \
  --data-urlencode 'role=platform-mechanism-receiver-dev' \
  "$api/v3/auth/role" >/dev/null || true

for permission in \
  "dev:SAAS_FORGE:config/platform-mechanism-receiver.yaml:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:r" \
  "dev:DEFAULT_GROUP:naming/platform-mechanism-receiver:w"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=platform-mechanism-receiver-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

# Gateway 在此隔离验收命名空间只获得测试接收端的发现读权限，生产 ACL 不变。
curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode 'role=gateway-dev' \
  --data-urlencode 'resource=dev:DEFAULT_GROUP:naming/platform-mechanism-receiver' \
  --data-urlencode 'action=r' \
  "$api/v3/auth/permission" >/dev/null || true
curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode 'role=config-publisher-dev' \
  --data-urlencode 'resource=dev:SAAS_FORGE:config/platform-mechanism-receiver.yaml' \
  --data-urlencode 'action=w' \
  "$api/v3/auth/permission" >/dev/null || true

publisher_token="$(login "$NACOS_PUBLISH_USERNAME" "$NACOS_PUBLISH_PASSWORD")"
test -n "$publisher_token"
curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=platform-mechanism-receiver.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/platform-mechanism-receiver.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

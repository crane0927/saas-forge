#!/bin/sh
set -eu

api="http://nacos:8848/nacos"

login() {
  curl --fail --silent --show-error --request POST \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "$api/v3/auth/user/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# Nacos 3 首次启动没有默认管理员密码；此接口只会在还不存在全局管理员时成功。
curl --silent --show-error --request POST \
  --data-urlencode "password=$NACOS_BOOTSTRAP_PASSWORD" \
  "$api/v3/auth/user/admin" >/dev/null || true

bootstrap_token="$(login nacos "$NACOS_BOOTSTRAP_PASSWORD")"
test -n "$bootstrap_token"

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode "namespaceName=$NACOS_NAMESPACE" \
  "$api/v3/admin/core/namespace" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_IAM_USERNAME" \
  --data-urlencode "password=$NACOS_IAM_PASSWORD" \
  "$api/v3/auth/user" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_IAM_USERNAME" \
  --data-urlencode 'role=iam-service-dev' \
  "$api/v3/auth/role" >/dev/null || true

# IAM 只读取自己的配置并注册自己的稳定服务名；配置发布仍由初始化身份负责。
for permission in \
  "dev:SAAS_FORGE:config/iam-service.yaml:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:w"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=iam-service-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode 'dataId=iam-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/iam-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

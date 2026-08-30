#!/usr/bin/env bash
set -Eeuo pipefail

for variable_name in \
    KAFKA_ADMIN_PASSWORD KAFKA_IAM_PASSWORD KAFKA_TENANT_ACCESS_PASSWORD \
    KAFKA_ENTITLEMENT_PASSWORD KAFKA_AUDIT_PASSWORD KAFKA_AUDIT_REPLAY_PASSWORD; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "缺少必填环境变量: ${variable_name}" >&2
        exit 1
    fi
done

readonly admin_config="$(mktemp)"
trap 'rm -f "$admin_config"' EXIT
chmod 0600 "$admin_config"
printf '%s\n' \
    'security.protocol=SASL_PLAINTEXT' \
    'sasl.mechanism=PLAIN' \
    "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"${KAFKA_ADMIN_PASSWORD}\";" \
    >"$admin_config"

readonly kafka_bin=/opt/kafka/bin
readonly bootstrap_server=kafka:9092

for _ in $(seq 1 60); do
    if "$kafka_bin/kafka-broker-api-versions.sh" \
        --bootstrap-server "$bootstrap_server" --command-config "$admin_config" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
"$kafka_bin/kafka-broker-api-versions.sh" \
    --bootstrap-server "$bootstrap_server" --command-config "$admin_config" >/dev/null

for topic in \
    saasforge.dev.iam-service.events \
    saasforge.dev.tenant-access-service.events \
    saasforge.dev.entitlement-service.events \
    saasforge.dev.audit-service.iam-session-isolations \
    saasforge.dev.audit-service.tenant-isolations; do
    "$kafka_bin/kafka-topics.sh" --bootstrap-server "$bootstrap_server" \
        --command-config "$admin_config" --create --if-not-exists \
        --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null
done

grant_acl() {
    local principal="$1"
    shift
    "$kafka_bin/kafka-acls.sh" --bootstrap-server "$bootstrap_server" \
        --command-config "$admin_config" --add --allow-principal "User:${principal}" "$@" >/dev/null
}

grant_acl iam-service --producer --topic saasforge.dev.iam-service.events
grant_acl tenant-access-service --producer --topic saasforge.dev.tenant-access-service.events
grant_acl entitlement-service --producer --topic saasforge.dev.entitlement-service.events

for topic in saasforge.dev.iam-service.events saasforge.dev.tenant-access-service.events; do
    grant_acl audit-service --operation Describe --topic "$topic"
    grant_acl audit-replay --producer --topic "$topic"
done
grant_acl audit-service --consumer --topic saasforge.dev.iam-service.events \
    --group audit-service.iam-session-events
grant_acl audit-service --consumer --topic saasforge.dev.tenant-access-service.events \
    --group audit-service.tenant-events
grant_acl audit-service --producer --topic saasforge.dev.audit-service.iam-session-isolations
grant_acl audit-service --producer --topic saasforge.dev.audit-service.tenant-isolations
grant_acl audit-service --operation Describe --cluster

# Replay Job 只能回投两个已确认来源 Topic，不能写隔离 Topic或消费任意 Topic。
grant_acl audit-replay --operation Describe --cluster

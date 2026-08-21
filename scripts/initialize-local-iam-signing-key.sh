#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"
readonly configuration_file="$(mktemp)"
compose_arguments=(
  --project-directory "$compose_directory"
  --file "$compose_directory/compose.yaml"
)

if [[ -n "${COMPOSE_PROJECT_NAME:-}" ]]; then
  compose_arguments+=(--project-name "$COMPOSE_PROJECT_NAME")
fi
if [[ -n "${LOCAL_COMPOSE_OVERRIDE_FILE:-}" ]]; then
  compose_arguments+=(--file "$LOCAL_COMPOSE_OVERRIDE_FILE")
fi

cleanup() {
  rm -f "$configuration_file"
}
trap cleanup EXIT

compose() {
  docker compose "${compose_arguments[@]}" "$@"
}

compose config --format json >"$configuration_file"

private_key_file="$(ruby -rjson -e '
  configuration = JSON.parse(File.read(ARGV.fetch(0)))
  mount = configuration.fetch("services").fetch("iam-service").fetch("volumes", []).find do |volume|
    volume["target"] == "/run/secrets/iam-jwt-private-key.pem"
  end
  abort "iam-service 缺少 JWT 私钥挂载" unless mount
  puts mount.fetch("source")
' "$configuration_file")"
key_version_ref="$(ruby -rjson -e '
  configuration = JSON.parse(File.read(ARGV.fetch(0)))
  puts configuration.fetch("services").fetch("iam-service").fetch("environment")
    .fetch("IAM_JWT_PEM_KEY_VERSION_REF")
' "$configuration_file")"

compose up --detach --wait postgres
compose run --rm iam-migrate

active_metadata="$(compose exec -T postgres sh -eu -c \
  'psql --username "$POSTGRES_USER" --dbname iam_db --tuples-only --no-align --field-separator "	" \
    --command "SELECT kid, key_version_reference, public_jwk_modulus, public_jwk_exponent
               FROM iam_signing_keys WHERE key_status = '\''ACTIVE'\''"')"

if [[ -n "$active_metadata" && ! -f "$private_key_file" ]]; then
  echo "IAM 已存在 ACTIVE Signing Key，但本地私钥文件不存在；拒绝生成不匹配的新私钥" >&2
  exit 1
fi

if [[ ! -f "$private_key_file" ]]; then
  mkdir -p "$(dirname "$private_key_file")"
  umask 077
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private_key_file"
fi
if [[ "$private_key_file" == "$compose_directory/.secrets/"* ]]; then
  chmod 700 "$compose_directory/.secrets"
fi
chmod 644 "$private_key_file"

metadata="$(ruby -ropenssl -rbase64 -rjson -rdigest -e '
  key = OpenSSL::PKey::RSA.new(File.binread(ARGV.fetch(0)))
  abort "本地 JWT 私钥必须包含私钥材料" unless key.private?
  encode = ->(number) { Base64.urlsafe_encode64(number.to_s(2), padding: false) }
  modulus = encode.call(key.n)
  exponent = encode.call(key.e)
  thumbprint_input = JSON.generate({"e" => exponent, "kty" => "RSA", "n" => modulus})
  kid = "local-#{Base64.urlsafe_encode64(Digest::SHA256.digest(thumbprint_input), padding: false)[0, 16]}"
  puts [kid, modulus, exponent].join("\t")
' "$private_key_file")"
IFS=$'\t' read -r kid modulus exponent <<<"$metadata"

if [[ -n "$active_metadata" ]]; then
  IFS=$'\t' read -r active_kid active_key_version_ref active_modulus active_exponent <<<"$active_metadata"
  if [[ "$active_key_version_ref" != "$key_version_ref" \
      || "$active_modulus" != "$modulus" \
      || "$active_exponent" != "$exponent" ]]; then
    echo "现有 ACTIVE Signing Key 与本地私钥或 Key Version 引用不匹配；请走显式轮换流程" >&2
    exit 1
  fi
  echo "本地 IAM Signing Key 已初始化: $active_kid"
  exit 0
fi

compose exec -T \
  -e LOCAL_JWT_KID="$kid" \
  -e LOCAL_JWT_KEY_VERSION_REF="$key_version_ref" \
  -e LOCAL_JWT_MODULUS="$modulus" \
  -e LOCAL_JWT_EXPONENT="$exponent" \
  postgres sh -eu -c '
    psql --username "$POSTGRES_USER" --dbname iam_db --set=ON_ERROR_STOP=1 \
      --set=kid="$LOCAL_JWT_KID" \
      --set=key_version_ref="$LOCAL_JWT_KEY_VERSION_REF" \
      --set=modulus="$LOCAL_JWT_MODULUS" \
      --set=exponent="$LOCAL_JWT_EXPONENT" <<'"'"'SQL'"'"'
INSERT INTO iam_signing_keys
    (kid, key_version_reference, public_jwk_modulus, public_jwk_exponent, key_status,
     max_issued_token_ttl_seconds, published_at, activated_at)
VALUES
    (:'"'"'kid'"'"', :'"'"'key_version_ref'"'"', :'"'"'modulus'"'"', :'"'"'exponent'"'"', '"'"'ACTIVE'"'"',
     0, now() - INTERVAL '"'"'5 minutes'"'"', now());
SQL
  '

echo "已生成本地 PKCS#8 RSA 私钥并初始化 IAM ACTIVE Signing Key: $kid"

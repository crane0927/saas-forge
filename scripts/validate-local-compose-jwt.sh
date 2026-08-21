#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/compose"

export IAM_JWT_ISSUER="${IAM_JWT_ISSUER:-https://api.saasforge.test}"
export IAM_JWT_PEM_KEY_VERSION_REF="${IAM_JWT_PEM_KEY_VERSION_REF:-local/pem/1}"
export IAM_JWT_PEM_PRIVATE_KEY_FILE="${IAM_JWT_PEM_PRIVATE_KEY_FILE:-/tmp/saas-forge-local-jwt.pem}"

configuration_file="$(mktemp)"
trap 'rm -f "$configuration_file"' EXIT
docker compose --project-directory "$compose_directory" \
  --file "$compose_directory/compose.yaml" config --format json >"$configuration_file"
ruby -rjson - "$compose_directory/.env.example" "$configuration_file" <<'RUBY'
env_example, configuration_file = ARGV
configuration = JSON.parse(File.read(configuration_file))
iam = configuration.fetch("services").fetch("iam-service")
environment = iam.fetch("environment", {})

expected_environment = {
  "IAM_JWT_ISSUER" => ENV.fetch("IAM_JWT_ISSUER"),
  "IAM_JWT_PEM_KEY_VERSION_REF" => ENV.fetch("IAM_JWT_PEM_KEY_VERSION_REF"),
  "IAM_JWT_PEM_PRIVATE_KEY_LOCATION" => "file:/run/secrets/iam-jwt-private-key.pem"
}
expected_environment.each do |name, expected|
  actual = environment[name]
  abort "iam-service 缺少正确的 #{name}: #{actual.inspect}" unless actual == expected
end

private_key_mount = iam.fetch("volumes", []).find do |volume|
  volume["target"] == "/run/secrets/iam-jwt-private-key.pem"
end
abort "iam-service 缺少 JWT 私钥挂载" unless private_key_mount
abort "IAM JWT 私钥必须只读挂载" unless private_key_mount["read_only"] == true

declared_variables = File.readlines(env_example, chomp: true)
  .map { |line| line[/\A([A-Z][A-Z0-9_]*)=/, 1] }
  .compact
required_variables = %w[IAM_JWT_ISSUER IAM_JWT_PEM_KEY_VERSION_REF IAM_JWT_PEM_PRIVATE_KEY_FILE]
missing_variables = required_variables - declared_variables
abort ".env.example 缺少变量: #{missing_variables.join(', ')}" unless missing_variables.empty?

puts "已校验本地 Compose JWT 配置、只读私钥挂载与 .env.example"
RUBY

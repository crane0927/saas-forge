#!/bin/sh
set -eu

umask 077
secret_dir="${1:-.secrets}"

command -v openssl >/dev/null 2>&1 || {
  echo "openssl is required" >&2
  exit 1
}

mkdir -p "$secret_dir"

for service in iam tenant-access entitlement; do
  for suffix in client-id client-secret; do
    path="$secret_dir/$service-$suffix"
    if [ -e "$path" ]; then
      echo "refusing to overwrite existing secret material: $path" >&2
      exit 1
    fi
  done
done

uuid_v7() {
  timestamp_ms="$(($(date +%s) * 1000))"
  timestamp_hex="$(printf '%012x' "$timestamp_ms")"
  random_hex="$(openssl rand -hex 10)"
  random_a="$(printf '%s' "$random_hex" | cut -c 1-3)"
  variant_source="$(printf '%s' "$random_hex" | cut -c 4)"
  variant="$(printf '%x' "$(((0x$variant_source & 3) | 8))")"
  random_b_head="$(printf '%s' "$random_hex" | cut -c 5-7)"
  random_b_tail="$(printf '%s' "$random_hex" | cut -c 8-19)"
  printf '%s-%s-7%s-%s%s-%s\n' \
    "$(printf '%s' "$timestamp_hex" | cut -c 1-8)" \
    "$(printf '%s' "$timestamp_hex" | cut -c 9-12)" \
    "$random_a" "$variant" "$random_b_head" "$random_b_tail"
}

client_secret() {
  openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
  printf '\n'
}

for service in iam tenant-access entitlement; do
  uuid_v7 >"$secret_dir/$service-client-id"
  client_secret >"$secret_dir/$service-client-secret"
done

echo "generated three UUIDv7 Client IDs and 256-bit Client Secrets in $secret_dir"

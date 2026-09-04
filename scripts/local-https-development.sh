#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=false
export COREPACK_ENABLE_NETWORK=0

exec mise exec node@24.14.1 -- node "$repository_root/scripts/local-https-development.mjs" "$@"

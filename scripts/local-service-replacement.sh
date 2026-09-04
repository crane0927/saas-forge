#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

exec mise exec node@24.14.1 -- node "$repository_root/scripts/local-service-replacement.mjs" "$@"

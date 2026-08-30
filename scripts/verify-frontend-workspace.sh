#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
console_root="$repository_root/consoles"
expected_node="24.14.1"
expected_pnpm="11.22.0"

if ! command -v node >/dev/null 2>&1; then
  echo "Frontend verification requires Node $expected_node; install it before running Maven verify." >&2
  exit 1
fi

if ! command -v pnpm >/dev/null 2>&1; then
  echo "Frontend verification requires pnpm $expected_pnpm; enable Corepack and install dependencies before running Maven verify." >&2
  exit 1
fi

actual_node="$(node --version)"
if [[ "$actual_node" != "v$expected_node" ]]; then
  echo "Frontend verification requires Node $expected_node, but found $actual_node." >&2
  exit 1
fi

# Corepack 会从当前目录向上查找 packageManager，必须在 workspace 根解析固定版本。
actual_pnpm="$(cd "$console_root" && pnpm --version)"
if [[ "$actual_pnpm" != "$expected_pnpm" ]]; then
  echo "Frontend verification requires pnpm $expected_pnpm, but found $actual_pnpm." >&2
  exit 1
fi

if [[ ! -d "$console_root/node_modules" ]]; then
  echo "Frontend dependencies are missing. Run 'pnpm install --frozen-lockfile' in consoles before Maven verify." >&2
  exit 1
fi

(cd "$console_root" && pnpm run verify:workspace)

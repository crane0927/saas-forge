#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 三类成功事实必须来自真实 IAM/Tenant Access 业务入口；跳过与 Audit 无关的 Tenant 深层负向矩阵。
AUDIT_SUCCESS_FACTS_ONLY=true exec bash "$repository_root/scripts/verify-tenant-lifecycle-e2e.sh"

package io.saasforge.entitlement.domain.plan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public record Plan(
        UUID id,
        String code,
        String displayName,
        PlanStatus status,
        List<PlanQuotaLimit> quotaLimits,
        Instant createdAt,
        Instant updatedAt) {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    public Plan {
        quotaLimits = List.copyOf(quotaLimits);
    }

    public static Plan draft(
            UUID id, String code, String displayName, PlanQuotaLimit quotaLimit, Instant now) {
        if (id == null || id.version() != 7) {
            throw new PlanInvalidException("Plan ID 必须是 UUIDv7");
        }
        validateDraftFields(code, displayName, quotaLimit);
        requirePositiveLimit(List.of(quotaLimit));
        return new Plan(id, code, displayName, PlanStatus.DRAFT, List.of(quotaLimit), now, now);
    }

    /** 校验与保留期无关的字段；新额度下限仍须在历史重放判定后执行。 */
    public static void validateDraftFields(String code, String displayName, PlanQuotaLimit quotaLimit) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new PlanInvalidException("Plan code 不合法");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new PlanInvalidException("Plan displayName 不合法");
        }
        if (quotaLimit == null) {
            throw new PlanInvalidException("Plan 必须恰好包含一个 max_users 限额");
        }
    }

    /** 历史读取允许零额度；仅新建、激活和新订阅授予使用新下限。 */
    public void requireNewGrantEligible() {
        requirePositiveLimit(quotaLimits);
    }

    private static void requirePositiveLimit(List<PlanQuotaLimit> limits) {
        if (limits.size() != 1 || limits.get(0).limit() < 1) {
            throw new PlanInvalidException("New Plan grants require max_users of at least 1");
        }
    }

    public Plan activate(Instant now) {
        if (status != PlanStatus.DRAFT) {
            throw new PlanTransitionException();
        }
        requireNewGrantEligible();
        return new Plan(id, code, displayName, PlanStatus.ACTIVE, quotaLimits, createdAt, now);
    }
}

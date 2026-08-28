package io.saasforge.audit.application;

import java.time.Instant;
import java.util.UUID;

/** 由 IAM Session Started 成功事实规范化得到的 Audit Record，不携带原始 Envelope。 */
public record SessionStartedAuditRecord(
        UUID sourceEventId,
        String source,
        String sourceType,
        Instant occurredAt,
        String traceId,
        UUID actorIdentityId,
        UUID resourceId,
        String metadata) {}

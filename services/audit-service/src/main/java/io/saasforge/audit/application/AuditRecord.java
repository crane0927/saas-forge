package io.saasforge.audit.application;

import java.time.Instant;
import java.util.UUID;

/** 由已授权成功事实规范化得到的 Audit Record，不携带原始 Envelope。 */
public record AuditRecord(
        UUID sourceEventId,
        String source,
        String sourceType,
        Instant occurredAt,
        String traceId,
        UUID actorIdentityId,
        UUID tenantId,
        String action,
        String resourceType,
        UUID resourceId,
        String metadata) {}

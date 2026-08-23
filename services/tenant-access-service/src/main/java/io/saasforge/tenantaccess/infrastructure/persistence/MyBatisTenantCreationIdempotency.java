package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.tenant.TenantCreationIdempotency;
import io.saasforge.tenantaccess.application.tenant.TenantCreationResult;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantCreationMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantCreationIdempotencyRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisTenantCreationIdempotency implements TenantCreationIdempotency {
    private final TenantCreationMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisTenantCreationIdempotency(TenantCreationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now) {
        mapper.deleteExpiredIdempotency(
                callerIdentityId, idempotencyKey, TenantAccessTime.asOffsetDateTime(now));
    }

    @Override
    public boolean claim(
            UUID callerIdentityId, UUID idempotencyKey, String fingerprint, UUID tenantId, Instant expiresAt) {
        return mapper.claimIdempotency(new TenantCreationIdempotencyRow(
                callerIdentityId, idempotencyKey, fingerprint, tenantId,
                null, null, null, TenantAccessTime.asOffsetDateTime(expiresAt))) == 1;
    }

    @Override
    public Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey) {
        return Optional.ofNullable(mapper.findIdempotency(callerIdentityId, idempotencyKey))
                .map(row -> new Entry(
                        row.requestFingerprint(), row.tenantId(),
                        row.responseBody() == null
                                ? null
                                : objectMapper.readValue(row.responseBody(), TenantCreationResult.class)));
    }

    @Override
    public void complete(
            UUID callerIdentityId, UUID idempotencyKey, TenantCreationResult result, Instant completedAt) {
        TenantCreationIdempotencyRow row = new TenantCreationIdempotencyRow(
                callerIdentityId, idempotencyKey, null, result.id(), 201,
                objectMapper.writeValueAsString(result), TenantAccessTime.asOffsetDateTime(completedAt), null);
        if (mapper.completeIdempotency(row) != 1) {
            throw new IllegalStateException("Tenant 创建幂等结果保存失败");
        }
    }
}

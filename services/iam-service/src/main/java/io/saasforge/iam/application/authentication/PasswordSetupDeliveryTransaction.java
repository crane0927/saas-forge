package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordSetupDelivery;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryRepository;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryStatus;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PasswordSetupDeliveryTransaction {
    private final PasswordSetupDeliveryRepository deliveries;
    private final IdentityRepository identities;
    private final PasswordSetupService passwordSetups;
    private final OutboxEventRepository outboxEvents;
    private final PasswordSetupDeliveredEventFactory eventFactory;
    private final Clock clock;

    public PasswordSetupDeliveryTransaction(
            PasswordSetupDeliveryRepository deliveries,
            IdentityRepository identities,
            PasswordSetupService passwordSetups,
            OutboxEventRepository outboxEvents,
            PasswordSetupDeliveredEventFactory eventFactory,
            Clock clock) {
        this.deliveries = deliveries;
        this.identities = identities;
        this.passwordSetups = passwordSetups;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    /** 同一 requestId 串行生成尝试；未完成重试必须替换旧 Challenge，不能恢复明文 Token。 */
    @Transactional
    public PasswordSetupDeliveryAttempt prepare(UUID callerClientId, UUID requestId, UUID identityId) {
        requireUuidV7(callerClientId, "callerClientId");
        requireUuidV7(requestId, "requestId");
        requireUuidV7(identityId, "identityId");
        deliveries.lockRequest(callerClientId, requestId);
        var existing = deliveries.find(callerClientId, requestId);
        if (existing.isPresent()) {
            PasswordSetupDelivery delivery = existing.orElseThrow();
            if (!delivery.identityId().equals(identityId)) {
                throw new PasswordSetupDeliveryRequestConflictException();
            }
            if (delivery.completed()) {
                return PasswordSetupDeliveryAttempt.completed(result(delivery.status()));
            }
        }

        identities.lockIdentity(identityId);
        Identity identity = identities.findById(identityId)
                .orElseThrow(() -> new IllegalArgumentException("Identity 不存在"));
        Instant now = now();
        var credentials = identities.findCredentials(identityId);
        if (credentials.stream().anyMatch(credential ->
                credential.type() == CredentialType.PASSWORD && credential.isValidAt(now))) {
            if (existing.isEmpty()) {
                deliveries.savePasswordReady(callerClientId, requestId, identityId, now);
            } else if (!deliveries.markPasswordReady(callerClientId, requestId, identityId, now)) {
                throw new IllegalStateException("Password Setup 投递请求无法完成已有密码短路");
            }
            return PasswordSetupDeliveryAttempt.completed(PasswordSetupDeliveryResult.PASSWORD_READY);
        }
        if (!credentials.isEmpty()) {
            throw new IdentityCredentialRecoveryRequiredException();
        }

        PasswordSetupChallengeToken challenge = passwordSetups.issueChallenge(identityId);
        deliveries.savePending(
                callerClientId, requestId, identityId, challenge.challengeId(), challenge.expiresAt());
        return PasswordSetupDeliveryAttempt.pending(
                challenge.challengeId(), identity.email().value(), challenge.value(), challenge.expiresAt());
    }

    /** 只有当前 requestId 仍指向 SMTP 接受的 Challenge 时，才固化成功并发布事实。 */
    @Transactional
    public boolean confirm(
            UUID callerClientId,
            UUID requestId,
            UUID identityId,
            UUID challengeId,
            Instant challengeExpiresAt,
            String traceId) {
        deliveries.lockRequest(callerClientId, requestId);
        Instant deliveredAt = now();
        if (!deliveries.markDelivered(callerClientId, requestId, challengeId, deliveredAt)) {
            return false;
        }
        outboxEvents.append(eventFactory.create(
                identityId, requestId, challengeExpiresAt, deliveredAt, traceId));
        return true;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static PasswordSetupDeliveryResult result(PasswordSetupDeliveryStatus status) {
        return switch (status) {
            case DELIVERED -> PasswordSetupDeliveryResult.DELIVERED;
            case PASSWORD_READY -> PasswordSetupDeliveryResult.PASSWORD_READY;
            case PENDING -> throw new IllegalArgumentException("未完成投递没有稳定结果");
        };
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}

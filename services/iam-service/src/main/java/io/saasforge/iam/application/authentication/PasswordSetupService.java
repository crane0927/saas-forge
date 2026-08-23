package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.identity.PasswordSetupChallenge;
import io.saasforge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PasswordSetupService {
    private static final Duration CHALLENGE_LIFETIME = Duration.ofHours(24);
    private static final Sha256Digest REQUEST_FINGERPRINT = operationFingerprint();

    private final PasswordSetupChallengeRepository challenges;
    private final IdentityRepository identities;
    private final PasswordSetupChallengeIssuer challengeIssuer;
    private final PasswordPolicy passwordPolicy;
    private final CompromisedPasswordChecker compromisedPasswords;
    private final PasswordVerifier passwordVerifier;
    private final OutboxEventRepository outboxEvents;
    private final PasswordEstablishedEventFactory eventFactory;
    private final Clock clock;

    public PasswordSetupService(
            PasswordSetupChallengeRepository challenges,
            IdentityRepository identities,
            PasswordSetupChallengeIssuer challengeIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordEstablishedEventFactory eventFactory,
            Clock clock) {
        this.challenges = challenges;
        this.identities = identities;
        this.challengeIssuer = challengeIssuer;
        this.passwordPolicy = passwordPolicy;
        this.compromisedPasswords = compromisedPasswords;
        this.passwordVerifier = passwordVerifier;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    /** 明文 Token 只作为本次返回值存在；旧 Challenge 的失效与新摘要的保存共享事务。 */
    @Transactional
    public PasswordSetupChallengeToken issueChallenge(UUID identityId) {
        if (identityId == null || identityId.version() != 7) {
            throw new IllegalArgumentException("identityId 必须是 UUIDv7");
        }
        Instant issuedAt = now();
        var material = challengeIssuer.issue();
        PasswordSetupChallenge challenge = challenges.replaceOpenChallenge(
                        identityId, material.digest(), issuedAt, issuedAt.plus(CHALLENGE_LIFETIME))
                .orElseThrow(PasswordSetupChallengeNotAllowedException::new);
        return new PasswordSetupChallengeToken(challenge.id(), material.token(), challenge.expiresAt());
    }

    /** Credential、Challenge 消费、稳定 204 事实和 Outbox 必须在同一事务提交。 */
    @Transactional
    public void establishPassword(UUID idempotencyKey, String token, String newPassword, String traceId) {
        requireUuidV7(idempotencyKey);
        Sha256Digest tokenDigest = challengeIssuer.digest(token);
        PasswordSetupChallenge located = challenges.findByTokenDigest(tokenDigest)
                .orElseThrow(PasswordSetupTokenInvalidException::new);
        // Challenge 重签也先锁 Identity；保持相同锁顺序，避免兑换与重签互相等待。
        identities.lockIdentity(located.identityId());
        PasswordSetupChallenge challenge = challenges.lockByTokenDigest(tokenDigest)
                .orElseThrow(PasswordSetupTokenInvalidException::new);
        if (challenge.isSuccessfulReplay(idempotencyKey)) {
            return;
        }
        Instant establishedAt = now();
        if (!challenge.canRedeemAt(establishedAt)) {
            throw new PasswordSetupTokenInvalidException();
        }

        String normalizedPassword = passwordPolicy.normalizeForChange(newPassword);
        if (compromisedPasswords.isCompromised(normalizedPassword)) {
            throw new PasswordCompromisedException();
        }
        PasswordCredential credential = identities.createFirstPassword(PasswordCredential.regular(
                        challenge.identityId(), passwordVerifier.hash(normalizedPassword), establishedAt))
                .orElseThrow(PasswordSetupTokenInvalidException::new);
        challenges.complete(
                challenge.id(), idempotencyKey, REQUEST_FINGERPRINT, credential.id(), establishedAt);
        outboxEvents.append(eventFactory.create(challenge.identityId(), credential, establishedAt, traceId));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireUuidV7(UUID key) {
        if (key == null || key.version() != 7) {
            throw new IllegalArgumentException("Idempotency-Key 必须是 UUIDv7");
        }
    }

    private static Sha256Digest operationFingerprint() {
        try {
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256").digest(
                    "POST\n/api/v1/auth/password-setups\nv1".getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}

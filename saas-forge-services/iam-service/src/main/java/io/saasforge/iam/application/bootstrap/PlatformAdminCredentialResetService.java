package io.saasforge.iam.application.bootstrap;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapState;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetRepository;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PlatformAdminCredentialResetService {
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final PlatformAdminBootstrapRepository bootstrapFacts;
    private final PlatformAdminCredentialResetRepository resetFacts;
    private final IdentityRepository identities;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final OutboxEventRepository outboxEvents;
    private final PlatformAdminCredentialResetEventFactory eventFactory;
    private final PasswordPolicy passwordPolicy;
    private final PasswordVerifier passwordVerifier;
    private final Clock clock;

    public PlatformAdminCredentialResetService(
            PlatformAdminBootstrapRepository bootstrapFacts,
            PlatformAdminCredentialResetRepository resetFacts,
            IdentityRepository identities,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            OutboxEventRepository outboxEvents,
            PlatformAdminCredentialResetEventFactory eventFactory,
            PasswordPolicy passwordPolicy,
            PasswordVerifier passwordVerifier,
            Clock clock) {
        this.bootstrapFacts = bootstrapFacts;
        this.resetFacts = resetFacts;
        this.identities = identities;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.passwordPolicy = passwordPolicy;
        this.passwordVerifier = passwordVerifier;
        this.clock = clock;
    }

    /** 旧凭据、INITIAL_PASSWORD_CHANGE Family、新凭据、幂等事实与 Outbox 必须共享同一事务。 */
    @Transactional
    public PlatformAdminCredentialResetResult reset(
            UUID resetRequestId, String newInitialPassword, String traceId) {
        requireUuidV7(resetRequestId);

        resetFacts.lockReset();
        var replay = resetFacts.findByRequestId(resetRequestId);
        if (replay.isPresent()) {
            return replay(replay.orElseThrow());
        }

        String normalizedPassword = passwordPolicy.normalizeForChange(newInitialPassword);
        Instant resetAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        // 与首次引导共用锁，防止在唯一 Default Platform Admin 尚未完整提交时开始重置。
        bootstrapFacts.lockInitialization();
        PlatformAdminBootstrapState bootstrap = bootstrapFacts.findState()
                .orElseThrow(PlatformAdminCredentialResetConflictException::new);
        requireDefaultPlatformAdmin(bootstrap, resetAt);
        UUID identityId = bootstrap.identity().id();

        // Password Change 先锁 Family 再替换凭据；保持相同顺序可避免相互等待形成死锁。
        refreshTokenFamilies.revokeInitialPasswordChangeFamilies(identityId, resetAt);
        List<PasswordCredential> credentials = identities.lockCredentials(identityId);
        if (credentials.stream().anyMatch(credential ->
                credential.type() == CredentialType.PASSWORD && credential.isValidAt(resetAt))) {
            throw new PlatformAdminCredentialResetConflictException();
        }
        credentials.stream()
                .filter(credential -> credential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD)
                .filter(credential -> credential.invalidatedAt() == null)
                .forEach(credential -> identities.invalidate(credential.id(), resetAt));

        PasswordCredential credential = identities.create(PasswordCredential.initial(
                identityId, passwordVerifier.hash(normalizedPassword), resetAt));
        OutboxEvent event = eventFactory.create(resetRequestId, identityId, credential, resetAt, traceId);
        outboxEvents.append(event);
        resetFacts.create(new PlatformAdminCredentialResetFact(
                resetRequestId, identityId, credential.id(), event.eventId(), resetAt));
        return result(PlatformAdminCredentialResetResult.Outcome.RESET, resetRequestId, identityId, credential);
    }

    private PlatformAdminCredentialResetResult replay(PlatformAdminCredentialResetFact fact) {
        PasswordCredential credential = identities.findCredential(fact.credentialId())
                .filter(current -> current.identityId().equals(fact.identityId()))
                .filter(current -> current.type() == CredentialType.INITIAL_PLATFORM_PASSWORD)
                .filter(current -> current.issuedAt().equals(fact.resetAt()))
                .orElseThrow(PlatformAdminCredentialResetConflictException::new);
        return result(PlatformAdminCredentialResetResult.Outcome.ALREADY_RESET,
                fact.resetRequestId(), fact.identityId(), credential);
    }

    private static void requireDefaultPlatformAdmin(PlatformAdminBootstrapState state, Instant at) {
        PlatformRoleAssignment role = state.roleAssignment();
        if (!state.fact().identityId().equals(state.identity().id())
                || !state.fact().identityId().equals(role.identityId())
                || !PLATFORM_ADMIN_ROLE.equals(role.roleKey())
                || !role.isActiveAt(at)) {
            throw new PlatformAdminCredentialResetConflictException();
        }
    }

    private static void requireUuidV7(UUID resetRequestId) {
        if (resetRequestId == null || resetRequestId.version() != 7) {
            throw new IllegalArgumentException("resetRequestId 必须是 UUIDv7");
        }
    }

    private static PlatformAdminCredentialResetResult result(
            PlatformAdminCredentialResetResult.Outcome outcome,
            UUID resetRequestId,
            UUID identityId,
            PasswordCredential credential) {
        return new PlatformAdminCredentialResetResult(
                outcome, resetRequestId, identityId, credential.id(), credential.expiresAt());
    }
}

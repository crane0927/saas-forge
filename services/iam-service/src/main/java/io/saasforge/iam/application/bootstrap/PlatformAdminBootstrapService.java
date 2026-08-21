package io.saasforge.iam.application.bootstrap;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapState;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class PlatformAdminBootstrapService {
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final Duration INITIAL_CREDENTIAL_LIFETIME = Duration.ofHours(24);

    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository platformRoles;
    private final PlatformAdminBootstrapRepository bootstrapFacts;
    private final OutboxEventRepository outboxEvents;
    private final PlatformAdminInitializedEventFactory eventFactory;
    private final PasswordPolicy passwordPolicy;
    private final PasswordVerifier passwordVerifier;
    private final Clock clock;

    public PlatformAdminBootstrapService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            PlatformAdminBootstrapRepository bootstrapFacts,
            OutboxEventRepository outboxEvents,
            PlatformAdminInitializedEventFactory eventFactory,
            PasswordPolicy passwordPolicy,
            PasswordVerifier passwordVerifier,
            Clock clock) {
        this.identities = identities;
        this.platformRoles = platformRoles;
        this.bootstrapFacts = bootstrapFacts;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.passwordPolicy = passwordPolicy;
        this.passwordVerifier = passwordVerifier;
        this.clock = clock;
    }

    /** Identity、初始凭据、角色、幂等事实与 Outbox 必须共享同一事务。 */
    @Transactional
    public PlatformAdminBootstrapResult bootstrap(String email, String initialPassword, String traceId) {
        NormalizedEmail normalizedEmail = NormalizedEmail.from(email);
        String normalizedPassword = passwordPolicy.normalizeForChange(initialPassword);
        Instant initializedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        bootstrapFacts.lockInitialization();
        return bootstrapFacts.findState()
                .map(state -> replay(state, normalizedEmail, normalizedPassword, initializedAt))
                .orElseGet(() -> initialize(normalizedEmail, normalizedPassword, initializedAt, traceId));
    }

    private PlatformAdminBootstrapResult initialize(
            NormalizedEmail email, String password, Instant initializedAt, String traceId) {
        if (identities.findByEmail(email).isPresent() || bootstrapFacts.hasUntrackedBootstrapState()) {
            throw new PlatformAdminBootstrapConflictException();
        }
        Identity identity = identities.create(Identity.register(email.value(), null, initializedAt));
        PasswordCredential credential = identities.create(PasswordCredential.initial(
                identity.id(), passwordVerifier.hash(password), initializedAt));
        PlatformRoleAssignment roleAssignment = platformRoles.grant(
                PlatformRoleAssignment.grant(identity.id(), PLATFORM_ADMIN_ROLE, initializedAt));
        OutboxEvent event = eventFactory.create(identity.id(), credential, roleAssignment, initializedAt, traceId);
        outboxEvents.append(event);
        bootstrapFacts.create(new PlatformAdminBootstrapFact(
                identity.id(), credential.id(), roleAssignment.id(), event.eventId(), initializedAt));
        return result(PlatformAdminBootstrapResult.Outcome.INITIALIZED, identity, credential, roleAssignment);
    }

    private PlatformAdminBootstrapResult replay(
            PlatformAdminBootstrapState state,
            NormalizedEmail email,
            String password,
            Instant replayedAt) {
        PlatformAdminBootstrapFact fact = state.fact();
        Identity identity = state.identity();
        PasswordCredential credential = state.credential();
        PlatformRoleAssignment role = state.roleAssignment();
        boolean matches = fact.identityId().equals(identity.id())
                && fact.credentialId().equals(credential.id())
                && fact.roleAssignmentId().equals(role.id())
                && identity.email().equals(email)
                && identity.displayName() == null
                && credential.identityId().equals(identity.id())
                && credential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD
                && credential.issuedAt().equals(fact.initializedAt())
                && credential.expiresAt().equals(credential.issuedAt().plus(INITIAL_CREDENTIAL_LIFETIME))
                && credential.isValidAt(replayedAt)
                && passwordVerifier.matches(password, credential.passwordHash())
                && role.identityId().equals(identity.id())
                && PLATFORM_ADMIN_ROLE.equals(role.roleKey())
                && role.assignedAt().equals(fact.initializedAt())
                && role.isActiveAt(replayedAt)
                && state.identityCredentialCount() == 1
                && state.identityRoleAssignmentCount() == 1;
        if (!matches) {
            throw new PlatformAdminBootstrapConflictException();
        }
        return result(PlatformAdminBootstrapResult.Outcome.ALREADY_INITIALIZED, identity, credential, role);
    }

    private static PlatformAdminBootstrapResult result(
            PlatformAdminBootstrapResult.Outcome outcome,
            Identity identity,
            PasswordCredential credential,
            PlatformRoleAssignment roleAssignment) {
        return new PlatformAdminBootstrapResult(
                outcome, identity.id(), credential.id(), roleAssignment.id(), credential.expiresAt());
    }
}

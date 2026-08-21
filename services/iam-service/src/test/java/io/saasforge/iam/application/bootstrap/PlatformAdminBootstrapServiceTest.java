package io.saasforge.iam.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapState;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PlatformAdminBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String EMAIL = "platform-admin@example.test";
    private static final String PASSWORD = "Random-Initial-Password-2026";
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";

    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final PlatformRoleAssignmentRepository roles = mock(PlatformRoleAssignmentRepository.class);
    private final PlatformAdminBootstrapRepository facts = mock(PlatformAdminBootstrapRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PasswordVerifier passwordVerifier = new PasswordVerifier();
    private PlatformAdminBootstrapService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PlatformAdminBootstrapService(
                identities,
                roles,
                facts,
                outbox,
                new PlatformAdminInitializedEventFactory(
                        new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), "test"),
                new PasswordPolicy(),
                passwordVerifier,
                clock);
    }

    @Test
    void createsAllFactsWithoutPuttingSecretsInEventOrResult() {
        UUID identityId = uuidV7();
        UUID credentialId = uuidV7();
        UUID roleId = uuidV7();
        when(facts.findState()).thenReturn(Optional.empty());
        when(identities.findByEmail(new NormalizedEmail(EMAIL))).thenReturn(Optional.empty());
        when(facts.hasUntrackedBootstrapState()).thenReturn(false);
        when(identities.create(any(Identity.class))).thenAnswer(invocation ->
                invocation.<Identity>getArgument(0).identifiedBy(identityId));
        when(identities.create(any(PasswordCredential.class))).thenAnswer(invocation ->
                invocation.<PasswordCredential>getArgument(0).identifiedBy(credentialId));
        when(roles.grant(any(PlatformRoleAssignment.class))).thenAnswer(invocation -> {
            PlatformRoleAssignment role = invocation.getArgument(0);
            return new PlatformRoleAssignment(roleId, role.identityId(), role.roleKey(), role.assignedAt(), null);
        });

        PlatformAdminBootstrapResult result = service.bootstrap(EMAIL, PASSWORD, TRACE_ID);

        assertEquals(PlatformAdminBootstrapResult.Outcome.INITIALIZED, result.outcome());
        assertEquals(NOW.plusSeconds(24 * 60 * 60), result.credentialExpiresAt());
        assertFalse(result.toString().contains(EMAIL));
        assertFalse(result.toString().contains(PASSWORD));

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        ArgumentCaptor<PlatformAdminBootstrapFact> factCaptor =
                ArgumentCaptor.forClass(PlatformAdminBootstrapFact.class);
        var order = inOrder(outbox, facts);
        order.verify(outbox).append(eventCaptor.capture());
        order.verify(facts).create(factCaptor.capture());
        String snapshot = eventCaptor.getValue().eventSnapshot();
        assertFalse(snapshot.contains(EMAIL));
        assertFalse(snapshot.contains(PASSWORD));
        assertFalse(snapshot.contains("argon2id"));
        assertEquals(eventCaptor.getValue().eventId(), factCaptor.getValue().eventId());
    }

    @Test
    void replaysOnlyWhenEmailCredentialAndRoleStateAreExactlyEqual() {
        PlatformAdminBootstrapState state = initializedState(PASSWORD, NOW);
        when(facts.findState()).thenReturn(Optional.of(state));

        PlatformAdminBootstrapResult result = service.bootstrap(" PLATFORM-ADMIN@EXAMPLE.TEST ", PASSWORD, TRACE_ID);

        assertEquals(PlatformAdminBootstrapResult.Outcome.ALREADY_INITIALIZED, result.outcome());
        verify(identities, never()).create(any(Identity.class));
        verify(outbox, never()).append(any(OutboxEvent.class));

        assertThrows(PlatformAdminBootstrapConflictException.class,
                () -> service.bootstrap(EMAIL, "Different-Random-Password-2026", TRACE_ID));
    }

    @Test
    void rejectsUntrackedStateWithoutCreatingOrOverwritingAnything() {
        when(facts.findState()).thenReturn(Optional.empty());
        when(identities.findByEmail(new NormalizedEmail(EMAIL))).thenReturn(Optional.empty());
        when(facts.hasUntrackedBootstrapState()).thenReturn(true);

        assertThrows(PlatformAdminBootstrapConflictException.class,
                () -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));

        verify(identities, never()).create(any(Identity.class));
        verify(roles, never()).grant(any(PlatformRoleAssignment.class));
        verify(outbox, never()).append(any(OutboxEvent.class));
        verify(facts, never()).create(any(PlatformAdminBootstrapFact.class));
    }

    @Test
    void rejectsAnExistingIdentityBeforeInspectingUntrackedState() {
        PlatformAdminBootstrapState existing = initializedState(PASSWORD, NOW);
        when(facts.findState()).thenReturn(Optional.empty());
        when(identities.findByEmail(new NormalizedEmail(EMAIL))).thenReturn(Optional.of(existing.identity()));

        assertThrows(PlatformAdminBootstrapConflictException.class,
                () -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));

        verify(facts, never()).hasUntrackedBootstrapState();
        verify(identities, never()).create(any(Identity.class));
    }

    @Test
    void rejectsEveryRoleAndCardinalityDriftDuringReplay() {
        PlatformAdminBootstrapState original = initializedState(PASSWORD, NOW);
        PlatformRoleAssignment role = original.roleAssignment();
        PlatformAdminBootstrapState[] driftedStates = {
            new PlatformAdminBootstrapState(
                    original.fact(),
                    Identity.restore(original.identity().id(), new NormalizedEmail(EMAIL), "Platform Admin", NOW),
                    original.credential(), role, 1, 1),
            withRole(original, new PlatformRoleAssignment(
                    role.id(), uuidV7(), role.roleKey(), role.assignedAt(), role.revokedAt())),
            withRole(original, new PlatformRoleAssignment(
                    role.id(), role.identityId(), "TENANT_ADMIN", role.assignedAt(), role.revokedAt())),
            withRole(original, new PlatformRoleAssignment(
                    role.id(), role.identityId(), role.roleKey(), NOW.plusSeconds(1), role.revokedAt())),
            withRole(original, new PlatformRoleAssignment(
                    role.id(), role.identityId(), role.roleKey(), role.assignedAt(), NOW)),
            new PlatformAdminBootstrapState(
                    original.fact(), original.identity(), original.credential(), role, 2, 1),
            new PlatformAdminBootstrapState(
                    original.fact(), original.identity(), original.credential(), role, 1, 2)
        };

        for (PlatformAdminBootstrapState drifted : driftedStates) {
            when(facts.findState()).thenReturn(Optional.of(drifted));
            assertThrows(PlatformAdminBootstrapConflictException.class,
                    () -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));
        }
    }

    private PlatformAdminBootstrapState initializedState(String password, Instant initializedAt) {
        UUID identityId = uuidV7();
        Identity identity = Identity.restore(identityId, new NormalizedEmail(EMAIL), null, initializedAt);
        PasswordCredential credential = PasswordCredential.initial(
                identityId, passwordVerifier.hash(password), initializedAt).identifiedBy(uuidV7());
        PlatformRoleAssignment role = new PlatformRoleAssignment(
                uuidV7(), identityId, "PLATFORM_ADMIN", initializedAt, null);
        PlatformAdminBootstrapFact fact = new PlatformAdminBootstrapFact(
                identityId, credential.id(), role.id(), uuidV7(), initializedAt);
        return new PlatformAdminBootstrapState(fact, identity, credential, role, 1, 1);
    }

    private static PlatformAdminBootstrapState withRole(
            PlatformAdminBootstrapState state, PlatformRoleAssignment role) {
        return new PlatformAdminBootstrapState(
                state.fact(), state.identity(), state.credential(), role,
                state.identityCredentialCount(), state.identityRoleAssignmentCount());
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0000000000007000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }
}

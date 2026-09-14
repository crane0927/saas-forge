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
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapState;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetRepository;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PlatformAdminCredentialResetServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T02:00:00Z");
    private static final String PASSWORD = "New-Random-Initial-Password-2026";
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";

    private final PlatformAdminBootstrapRepository bootstrapFacts = mock(PlatformAdminBootstrapRepository.class);
    private final PlatformAdminCredentialResetRepository resetFacts =
            mock(PlatformAdminCredentialResetRepository.class);
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final RefreshTokenFamilyRepository families = mock(RefreshTokenFamilyRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PasswordVerifier passwordVerifier = new PasswordVerifier();
    private PlatformAdminCredentialResetService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PlatformAdminCredentialResetService(
                bootstrapFacts,
                resetFacts,
                identities,
                families,
                outbox,
                new PlatformAdminCredentialResetEventFactory(
                        new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), "test"),
                new PasswordPolicy(),
                passwordVerifier,
                clock);
    }

    @Test
    void invalidatesAllOldInitialCredentialsRevokesFamiliesAndPersistsOnlySafeFacts() {
        UUID resetRequestId = uuidV7();
        PlatformAdminBootstrapState bootstrap = bootstrapState();
        UUID identityId = bootstrap.identity().id();
        PasswordCredential first = bootstrap.credential();
        PasswordCredential expired = PasswordCredential.initial(
                identityId, passwordVerifier.hash("Old-Expired-Password-2026"), NOW.minusSeconds(48 * 60 * 60))
                .identifiedBy(uuidV7());
        when(resetFacts.findByRequestId(resetRequestId)).thenReturn(Optional.empty());
        when(bootstrapFacts.findState()).thenReturn(Optional.of(bootstrap));
        when(identities.lockCredentials(identityId)).thenReturn(List.of(first, expired));
        when(identities.create(any(PasswordCredential.class))).thenAnswer(invocation ->
                invocation.<PasswordCredential>getArgument(0).identifiedBy(uuidV7()));

        PlatformAdminCredentialResetResult result = service.reset(resetRequestId, PASSWORD, TRACE_ID);

        assertEquals(PlatformAdminCredentialResetResult.Outcome.RESET, result.outcome());
        assertEquals(NOW.plusSeconds(24 * 60 * 60), result.credentialExpiresAt());
        var order = inOrder(resetFacts, bootstrapFacts, families, identities, outbox);
        order.verify(resetFacts).lockReset();
        order.verify(resetFacts).findByRequestId(resetRequestId);
        order.verify(bootstrapFacts).lockInitialization();
        order.verify(bootstrapFacts).findState();
        order.verify(families).revokeInitialPasswordChangeFamilies(identityId, NOW);
        order.verify(identities).lockCredentials(identityId);
        order.verify(identities).invalidate(first.id(), NOW);
        order.verify(identities).invalidate(expired.id(), NOW);
        order.verify(identities).create(any(PasswordCredential.class));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        ArgumentCaptor<PlatformAdminCredentialResetFact> fact =
                ArgumentCaptor.forClass(PlatformAdminCredentialResetFact.class);
        order.verify(outbox).append(event.capture());
        order.verify(resetFacts).create(fact.capture());
        assertEquals(resetRequestId, fact.getValue().resetRequestId());
        assertEquals(event.getValue().eventId(), fact.getValue().eventId());
        assertFalse(event.getValue().eventSnapshot().contains(PASSWORD));
        assertFalse(event.getValue().eventSnapshot().contains("argon2id"));
        assertFalse(result.toString().contains(PASSWORD));
    }

    @Test
    void replaysCommittedRequestWithoutRevalidatingOrRegeneratingPassword() {
        UUID requestId = uuidV7();
        UUID identityId = uuidV7();
        PasswordCredential credential = PasswordCredential.initial(
                identityId, passwordVerifier.hash(PASSWORD), NOW.minusSeconds(60)).identifiedBy(uuidV7());
        PlatformAdminCredentialResetFact fact = new PlatformAdminCredentialResetFact(
                requestId, identityId, credential.id(), uuidV7(), credential.issuedAt());
        when(resetFacts.findByRequestId(requestId)).thenReturn(Optional.of(fact));
        when(identities.findCredential(credential.id())).thenReturn(Optional.of(credential));

        PlatformAdminCredentialResetResult result = service.reset(requestId, "short", TRACE_ID);

        assertEquals(PlatformAdminCredentialResetResult.Outcome.ALREADY_RESET, result.outcome());
        assertEquals(credential.id(), result.credentialId());
        verify(bootstrapFacts, never()).lockInitialization();
        verify(families, never()).revokeInitialPasswordChangeFamilies(any(), any());
        verify(identities, never()).create(any(PasswordCredential.class));
        verify(outbox, never()).append(any());
    }

    @Test
    void rejectsValidRegularPasswordWithoutCreatingAResetFact() {
        UUID requestId = uuidV7();
        PlatformAdminBootstrapState bootstrap = bootstrapState();
        UUID identityId = bootstrap.identity().id();
        PasswordCredential regular = PasswordCredential.regular(
                identityId, passwordVerifier.hash("Established-Password-2026"), NOW.minusSeconds(1))
                .identifiedBy(uuidV7());
        when(resetFacts.findByRequestId(requestId)).thenReturn(Optional.empty());
        when(bootstrapFacts.findState()).thenReturn(Optional.of(bootstrap));
        when(identities.lockCredentials(identityId)).thenReturn(List.of(bootstrap.credential(), regular));

        assertThrows(PlatformAdminCredentialResetConflictException.class,
                () -> service.reset(requestId, PASSWORD, TRACE_ID));

        verify(identities, never()).invalidate(any(), any());
        verify(identities, never()).create(any(PasswordCredential.class));
        verify(outbox, never()).append(any());
        verify(resetFacts, never()).create(any());
    }

    @Test
    void rejectsMissingOrMismatchedDefaultPlatformAdmin() {
        UUID requestId = uuidV7();
        when(resetFacts.findByRequestId(requestId)).thenReturn(Optional.empty());
        when(bootstrapFacts.findState()).thenReturn(Optional.empty());
        assertThrows(PlatformAdminCredentialResetConflictException.class,
                () -> service.reset(requestId, PASSWORD, TRACE_ID));

        PlatformAdminBootstrapState state = bootstrapState();
        PlatformAdminBootstrapState mismatched = new PlatformAdminBootstrapState(
                state.fact(),
                Identity.restore(uuidV7(), new NormalizedEmail("wrong@example.test"), null, NOW.minusSeconds(60)),
                state.credential(), state.roleAssignment(), 1, 1);
        when(bootstrapFacts.findState()).thenReturn(Optional.of(mismatched));
        assertThrows(PlatformAdminCredentialResetConflictException.class,
                () -> service.reset(uuidV7(), PASSWORD, TRACE_ID));

        verify(families, never()).revokeInitialPasswordChangeFamilies(any(), any());
    }

    @Test
    void rejectsNonUuidV7RequestBeforeTakingTheResetLock() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reset(UUID.randomUUID(), PASSWORD, TRACE_ID));
        verify(resetFacts, never()).lockReset();
    }

    private PlatformAdminBootstrapState bootstrapState() {
        UUID identityId = uuidV7();
        Identity identity = Identity.restore(
                identityId, new NormalizedEmail("platform-admin@example.test"), null, NOW.minusSeconds(60));
        PasswordCredential credential = PasswordCredential.initial(
                identityId, passwordVerifier.hash("Old-Initial-Password-2026"), NOW.minusSeconds(60))
                .identifiedBy(uuidV7());
        PlatformRoleAssignment role = new PlatformRoleAssignment(
                uuidV7(), identityId, "PLATFORM_ADMIN", NOW.minusSeconds(60), null);
        PlatformAdminBootstrapFact fact = new PlatformAdminBootstrapFact(
                identityId, credential.id(), role.id(), uuidV7(), NOW.minusSeconds(60));
        return new PlatformAdminBootstrapState(fact, identity, credential, role, 1, 1);
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0000000000007000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }
}

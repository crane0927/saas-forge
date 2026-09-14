package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.identity.PasswordSetupDelivery;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryRepository;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryStatus;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PasswordSetupDeliveryTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5101");
    private static final UUID REQUEST_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5102");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5103");
    private static final UUID OTHER_IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5104");
    private static final UUID CHALLENGE_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5105");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(86_400);

    private PasswordSetupDeliveryRepository deliveries;
    private IdentityRepository identities;
    private PasswordSetupService passwordSetups;
    private OutboxEventRepository outbox;
    private PasswordSetupDeliveredEventFactory events;
    private PasswordSetupDeliveryTransaction transaction;

    @BeforeEach
    void setUp() {
        deliveries = Mockito.mock(PasswordSetupDeliveryRepository.class);
        identities = Mockito.mock(IdentityRepository.class);
        passwordSetups = Mockito.mock(PasswordSetupService.class);
        outbox = Mockito.mock(OutboxEventRepository.class);
        events = Mockito.mock(PasswordSetupDeliveredEventFactory.class);
        transaction = new PasswordSetupDeliveryTransaction(
                deliveries, identities, passwordSetups, outbox, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(identities.findById(IDENTITY_ID)).thenReturn(Optional.of(Identity.restore(
                IDENTITY_ID, new NormalizedEmail("authority@example.test"), "Admin", NOW.minusSeconds(60))));
    }

    @Test
    void readsEmailFromAuthorityAndPersistsOnlyChallengeIdentity() {
        when(deliveries.find(CLIENT_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(identities.findCredentials(IDENTITY_ID)).thenReturn(List.of());
        when(passwordSetups.issueChallenge(IDENTITY_ID))
                .thenReturn(new PasswordSetupChallengeToken(CHALLENGE_ID, "B".repeat(43), EXPIRES_AT));

        PasswordSetupDeliveryAttempt attempt = transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID);

        assertEquals("authority@example.test", attempt.recipient());
        verify(deliveries).savePending(CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, EXPIRES_AT);
    }

    @Test
    void existingRegularPasswordIsStableSuccessWithoutChallenge() {
        when(deliveries.find(CLIENT_ID, REQUEST_ID)).thenReturn(Optional.empty());
        PasswordCredential password = PasswordCredential.regular(
                IDENTITY_ID, new PasswordVerifier().hash("Existing-Password-2026"), NOW.minusSeconds(1))
                .identifiedBy(UUID.randomUUID());
        when(identities.findCredentials(IDENTITY_ID)).thenReturn(List.of(password));

        PasswordSetupDeliveryAttempt result = transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID);

        assertEquals(PasswordSetupDeliveryResult.PASSWORD_READY, result.completedResult());
        verify(deliveries).savePasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, NOW);
        verify(passwordSetups, never()).issueChallenge(IDENTITY_ID);
    }

    @Test
    void anyHistoricalNonReadyCredentialRequiresRecovery() {
        when(deliveries.find(CLIENT_ID, REQUEST_ID)).thenReturn(Optional.empty());
        PasswordCredential invalidated = PasswordCredential.regular(
                        IDENTITY_ID, new PasswordVerifier().hash("Old-Password-2026"), NOW.minusSeconds(10))
                .identifiedBy(UUID.randomUUID())
                .invalidate(NOW.minusSeconds(1));
        when(identities.findCredentials(IDENTITY_ID)).thenReturn(List.of(invalidated));

        assertThrows(IdentityCredentialRecoveryRequiredException.class,
                () -> transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID));
        verify(passwordSetups, never()).issueChallenge(IDENTITY_ID);
    }

    @Test
    void requestIdCannotBeReboundAndCompletedResultNeverTouchesIdentity() {
        when(deliveries.find(CLIENT_ID, REQUEST_ID)).thenReturn(Optional.of(new PasswordSetupDelivery(
                CLIENT_ID, REQUEST_ID, OTHER_IDENTITY_ID, PasswordSetupDeliveryStatus.PASSWORD_READY,
                null, null, NOW)));
        assertThrows(PasswordSetupDeliveryRequestConflictException.class,
                () -> transaction.prepare(CLIENT_ID, REQUEST_ID, IDENTITY_ID));
        verify(identities, never()).findById(IDENTITY_ID);
    }

    @Test
    void publishesOnlyAfterCurrentChallengeIsConditionallyConfirmed() {
        OutboxEvent event = Mockito.mock(OutboxEvent.class);
        when(deliveries.markDelivered(CLIENT_ID, REQUEST_ID, CHALLENGE_ID, NOW)).thenReturn(true);
        when(events.create(IDENTITY_ID, REQUEST_ID, EXPIRES_AT, NOW, null)).thenReturn(event);

        transaction.confirm(CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, EXPIRES_AT, null);

        verify(outbox).append(event);
    }
}

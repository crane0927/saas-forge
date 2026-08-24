package io.saasforge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.identity.PasswordSetupDeliveryStatus;
import io.saasforge.iam.infrastructure.persistence.mapper.PasswordSetupDeliveryMapper;
import io.saasforge.iam.infrastructure.persistence.record.PasswordSetupDeliveryRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MyBatisPasswordSetupDeliveryRepositoryTest {
    private static final UUID CLIENT_ID = UUID.fromString("019535d9-0000-7000-8000-000000000011");
    private static final UUID REQUEST_ID = UUID.fromString("019535d9-0000-7000-8000-000000000012");
    private static final UUID IDENTITY_ID = UUID.fromString("019535d9-0000-7000-8000-000000000013");
    private static final UUID CHALLENGE_ID = UUID.fromString("019535d9-0000-7000-8000-000000000014");
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private PasswordSetupDeliveryMapper mapper;
    private MyBatisPasswordSetupDeliveryRepository repository;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(PasswordSetupDeliveryMapper.class);
        repository = new MyBatisPasswordSetupDeliveryRepository(mapper);
    }

    @Test
    void locksAndReadsStableDeliveryFacts() {
        when(mapper.lockRequest(CLIENT_ID + ":" + REQUEST_ID)).thenReturn(1);
        repository.lockRequest(CLIENT_ID, REQUEST_ID);
        when(mapper.lockRequest(CLIENT_ID + ":" + REQUEST_ID)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> repository.lockRequest(CLIENT_ID, REQUEST_ID));

        assertTrue(repository.find(CLIENT_ID, REQUEST_ID).isEmpty());
        PasswordSetupDeliveryRow row = new PasswordSetupDeliveryRow();
        row.setCallerClientId(CLIENT_ID);
        row.setRequestId(REQUEST_ID);
        row.setIdentityId(IDENTITY_ID);
        row.setStatus(PasswordSetupDeliveryStatus.DELIVERED.name());
        row.setChallengeId(CHALLENGE_ID);
        row.setChallengeExpiresAt(OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        row.setCompletedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(mapper.find(CLIENT_ID, REQUEST_ID)).thenReturn(row);

        var delivery = repository.find(CLIENT_ID, REQUEST_ID).orElseThrow();
        assertEquals(PasswordSetupDeliveryStatus.DELIVERED, delivery.status());
        assertEquals(CHALLENGE_ID, delivery.challengeId());
        assertEquals(NOW.plusSeconds(60), delivery.challengeExpiresAt());
        assertEquals(NOW, delivery.completedAt());
    }

    @Test
    void savesPasswordReadyAndRejectsLostInsert() {
        when(mapper.insertPasswordReady(any())).thenReturn(1);
        repository.savePasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, NOW);

        ArgumentCaptor<PasswordSetupDeliveryRow> row = ArgumentCaptor.forClass(PasswordSetupDeliveryRow.class);
        verify(mapper).insertPasswordReady(row.capture());
        assertEquals(CLIENT_ID, row.getValue().getCallerClientId());
        assertEquals(REQUEST_ID, row.getValue().getRequestId());
        assertEquals(IDENTITY_ID, row.getValue().getIdentityId());
        assertEquals(NOW, row.getValue().getCompletedAt().toInstant());

        when(mapper.insertPasswordReady(any())).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> repository.savePasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, NOW));
    }

    @Test
    void savesPendingAndRejectsConflictingRequest() {
        when(mapper.upsertPending(any())).thenReturn(1);
        repository.savePending(CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, NOW.plusSeconds(60));

        ArgumentCaptor<PasswordSetupDeliveryRow> row = ArgumentCaptor.forClass(PasswordSetupDeliveryRow.class);
        verify(mapper).upsertPending(row.capture());
        assertEquals(CHALLENGE_ID, row.getValue().getChallengeId());
        assertEquals(NOW.plusSeconds(60), row.getValue().getChallengeExpiresAt().toInstant());

        when(mapper.upsertPending(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> repository.savePending(
                CLIENT_ID, REQUEST_ID, IDENTITY_ID, CHALLENGE_ID, NOW.plusSeconds(60)));
    }

    @Test
    void exposesConditionalUpdateResults() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(mapper.markPasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, now)).thenReturn(1, 0);
        assertTrue(repository.markPasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, NOW));
        assertFalse(repository.markPasswordReady(CLIENT_ID, REQUEST_ID, IDENTITY_ID, NOW));

        when(mapper.markDelivered(CLIENT_ID, REQUEST_ID, CHALLENGE_ID, now)).thenReturn(1, 0);
        assertTrue(repository.markDelivered(CLIENT_ID, REQUEST_ID, CHALLENGE_ID, NOW));
        assertFalse(repository.markDelivered(CLIENT_ID, REQUEST_ID, CHALLENGE_ID, NOW));
    }
}

package io.saasforge.iam.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacement;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacementRepository;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ReservedServiceClientReplacementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("0198f300-0000-7000-8000-000000000001");
    private static final UUID OLD_CLIENT_ID = UUID.fromString("0198f300-0000-7000-8000-000000000002");
    private static final UUID NEW_CLIENT_ID = UUID.fromString("0198f300-0000-7000-8000-000000000003");
    private static final UUID EVENT_ID = UUID.fromString("0198f300-0000-7000-8000-000000000004");
    private static final String SECRET = secret((byte) 41);

    private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
    private final ReservedServiceClientReplacementRepository replacements =
            mock(ReservedServiceClientReplacementRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final UuidV7Generator ids = mock(UuidV7Generator.class);
    private final ReservedServiceClientReplacementService service = new ReservedServiceClientReplacementService(
            clients,
            replacements,
            outbox,
            new OAuthClientCreatedEventFactory(new ObjectMapper(), ids, "test"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(ids.next()).thenReturn(EVENT_ID);
        when(replacements.find(REQUEST_ID)).thenReturn(Optional.empty());
        when(clients.findBootstrapState(OLD_CLIENT_ID)).thenReturn(Optional.of(revokedOldClient()));
        when(clients.findById(NEW_CLIENT_ID)).thenReturn(Optional.empty());
        when(clients.findActiveByReservedServiceKey(ReservedServiceClient.IAM.serviceKey()))
                .thenReturn(Optional.empty());
        when(clients.createWithId(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void replacesRevokedClientAndAppendsDeploymentCreatedEventWithoutCredential() {
        ReservedServiceClientReplacementResult result = service.replace(input(SECRET), null);

        assertEquals(ReservedServiceClientReplacementResult.Outcome.REPLACED, result.outcome());
        ArgumentCaptor<OAuthClient> client = ArgumentCaptor.forClass(OAuthClient.class);
        verify(clients).createWithId(client.capture(), any(), any());
        assertEquals(NEW_CLIENT_ID, client.getValue().id());
        assertEquals(ReservedServiceClient.IAM.serviceKey(), client.getValue().reservedServiceKey());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(event.capture());
        assertTrue(event.getValue().eventSnapshot().contains("\"actorType\":\"DEPLOYMENT\""));
        assertTrue(event.getValue().eventSnapshot().contains(
                "\"deploymentOperationId\":\"" + REQUEST_ID + "\""));
        assertFalse(event.getValue().eventSnapshot().contains(SECRET));
        assertFalse(event.getValue().eventSnapshot().toLowerCase().contains("digest"));
    }

    @Test
    void exactReplayReturnsAlreadyReplacedAndSecretChangeConflicts() {
        service.replace(input(SECRET), null);
        ArgumentCaptor<ReservedServiceClientReplacement> stored =
                ArgumentCaptor.forClass(ReservedServiceClientReplacement.class);
        verify(replacements).append(stored.capture());
        when(replacements.find(REQUEST_ID)).thenReturn(Optional.of(stored.getValue()));

        ReservedServiceClientReplacementResult replay = service.replace(input(SECRET), null);
        assertEquals(ReservedServiceClientReplacementResult.Outcome.ALREADY_REPLACED, replay.outcome());

        ReservedServiceClientReplacementException conflict = assertThrows(
                ReservedServiceClientReplacementException.class,
                () -> service.replace(input(secret((byte) 42)), null));
        assertEquals(ReservedServiceClientReplacementException.Reason.REQUEST_CONFLICT, conflict.reason());
    }

    @Test
    void activeOldClientCannotBeReplaced() {
        OAuthClient active = OAuthClient.register(
                        ReservedServiceClient.IAM.displayName(), ReservedServiceClient.IAM.allowedScopes(), NOW)
                .identifiedBy(OLD_CLIENT_ID);
        when(clients.findBootstrapState(OLD_CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientBootstrapState(active, List.of())));

        ReservedServiceClientReplacementException exception = assertThrows(
                ReservedServiceClientReplacementException.class, () -> service.replace(input(SECRET), null));

        assertEquals(ReservedServiceClientReplacementException.Reason.OLD_CLIENT_NOT_REVOKED, exception.reason());
        verify(clients, never()).createWithId(any(), any(), any());
    }

    private static ReservedServiceClientReplacementInput input(String secret) {
        return new ReservedServiceClientReplacementInput(
                REQUEST_ID, ReservedServiceClient.IAM, OLD_CLIENT_ID, NEW_CLIENT_ID, secret);
    }

    private static OAuthClientBootstrapState revokedOldClient() {
        OAuthClient client = OAuthClient.restore(
                OLD_CLIENT_ID,
                ReservedServiceClient.IAM.displayName(),
                ReservedServiceClient.IAM.allowedScopes(),
                OAuthClientStatus.REVOKED,
                NOW.minusSeconds(100),
                NOW.minusSeconds(10));
        return new OAuthClientBootstrapState(client, List.of(new OAuthClientBootstrapState.SecretState(
                ClientSecretDigest.fromPlaintext(secret((byte) 40)), null, NOW.minusSeconds(10))));
    }

    private static String secret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

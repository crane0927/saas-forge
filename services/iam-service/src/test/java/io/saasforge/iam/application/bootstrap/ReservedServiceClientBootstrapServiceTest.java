package io.saasforge.iam.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.OAuthScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservedServiceClientBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final String IAM_SECRET = secret((byte) 1);
    private static final String TENANT_ACCESS_SECRET = secret((byte) 2);
    private static final String ENTITLEMENT_SECRET = secret((byte) 3);

    private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
    private final ReservedServiceClientBootstrapService service = new ReservedServiceClientBootstrapService(
            clients, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAllThreeFixedClientsInOneBootstrapCall() {
        inputs().forEach(input -> when(clients.findBootstrapState(input.clientId())).thenReturn(Optional.empty()));

        ReservedServiceClientBootstrapResult result = service.bootstrap(inputs());

        verify(clients).lockReservedClientBootstrap();
        verify(clients, org.mockito.Mockito.times(3)).createWithId(any(), any(), any());
        assertEquals(3, result.clients().size());
        assertEquals(ReservedServiceClientBootstrapResult.Outcome.INITIALIZED,
                result.clients().get(ReservedServiceClient.IAM).outcome());
        assertTrue(ReservedServiceClient.TENANT_ACCESS.allowedScopes().contains(OAuthScope.IAM_SESSIONS_WRITE));
    }

    @Test
    void exactStateIsIdempotent() {
        inputs().forEach(input -> when(clients.findBootstrapState(input.clientId()))
                .thenReturn(Optional.of(state(input))));

        ReservedServiceClientBootstrapResult result = service.bootstrap(inputs());

        verify(clients, never()).createWithId(any(), any(), any());
        assertEquals(ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED,
                result.clients().get(ReservedServiceClient.TENANT_ACCESS).outcome());
    }

    @Test
    void rotatedClientAcceptsAnyCurrentlyValidMountedSecretWithoutWriting() {
        ReservedServiceClientBootstrapInput iam = inputs().get(0);
        OAuthClient client = OAuthClient.register(
                        iam.service().displayName(), iam.service().allowedScopes(), NOW.minusSeconds(10))
                .identifiedBy(iam.clientId());
        when(clients.findBootstrapState(iam.clientId())).thenReturn(Optional.of(new OAuthClientBootstrapState(
                client,
                List.of(
                        new OAuthClientBootstrapState.SecretState(
                                ClientSecretDigest.fromPlaintext(iam.clientSecret()), NOW.plusSeconds(60), null),
                        new OAuthClientBootstrapState.SecretState(
                                ClientSecretDigest.fromPlaintext(secret((byte) 9)), null, null)))));
        inputs().stream().skip(1).forEach(input -> when(clients.findBootstrapState(input.clientId()))
                .thenReturn(Optional.of(state(input))));

        ReservedServiceClientBootstrapResult result = service.bootstrap(inputs());

        verify(clients, never()).createWithId(any(), any(), any());
        assertEquals(ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED,
                result.clients().get(ReservedServiceClient.IAM).outcome());
    }

    @Test
    void expiredMountedSecretRequiresExternalSecretUpdate() {
        ReservedServiceClientBootstrapInput iam = inputs().get(0);
        OAuthClient client = OAuthClient.register(
                        iam.service().displayName(), iam.service().allowedScopes(), NOW.minusSeconds(10))
                .identifiedBy(iam.clientId());
        when(clients.findBootstrapState(iam.clientId())).thenReturn(Optional.of(new OAuthClientBootstrapState(
                client,
                List.of(new OAuthClientBootstrapState.SecretState(
                        ClientSecretDigest.fromPlaintext(iam.clientSecret()), NOW, null)))));

        ReservedServiceClientBootstrapConflictException exception = assertThrows(
                ReservedServiceClientBootstrapConflictException.class, () -> service.bootstrap(inputs()));

        assertEquals(ReservedServiceClientBootstrapConflictException.Reason.MOUNTED_SECRET_NOT_CURRENT,
                exception.reason());
        verify(clients, never()).createWithId(any(), any(), any());
    }

    @Test
    void differentIdCannotBootstrapOverRevokedServiceIdentity() {
        ReservedServiceClientBootstrapInput iam = inputs().get(0);
        OAuthClient revoked = OAuthClient.restore(
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca0"),
                iam.service().displayName(), iam.service().allowedScopes(),
                OAuthClientStatus.REVOKED, NOW.minusSeconds(10), NOW.minusSeconds(1));
        when(clients.findBootstrapState(iam.clientId())).thenReturn(Optional.empty());
        when(clients.findAnyByReservedServiceKey(iam.service().serviceKey())).thenReturn(Optional.of(revoked));

        ReservedServiceClientBootstrapConflictException exception = assertThrows(
                ReservedServiceClientBootstrapConflictException.class, () -> service.bootstrap(inputs()));

        assertEquals(ReservedServiceClientBootstrapConflictException.Reason.CLIENT_REVOKED, exception.reason());
        verify(clients, never()).createWithId(any(), any(), any());
    }

    @Test
    void secretStatusScopeOrExtraSecretDriftFailsWithoutReconciliation() {
        ReservedServiceClientBootstrapInput iam = inputs().get(0);
        OAuthClient expected = OAuthClient.restore(
                iam.clientId(), iam.service().displayName(), iam.service().allowedScopes(),
                OAuthClientStatus.REVOKED, NOW.minusSeconds(10), NOW.minusSeconds(1));
        when(clients.findBootstrapState(iam.clientId())).thenReturn(Optional.of(new OAuthClientBootstrapState(
                expected,
                List.of(
                        new OAuthClientBootstrapState.SecretState(
                                ClientSecretDigest.fromPlaintext(iam.clientSecret()), null, null),
                        new OAuthClientBootstrapState.SecretState(
                                ClientSecretDigest.fromPlaintext(secret((byte) 9)), null, null)))));

        ReservedServiceClientBootstrapConflictException exception = assertThrows(
                ReservedServiceClientBootstrapConflictException.class, () -> service.bootstrap(inputs()));
        assertEquals(ReservedServiceClientBootstrapConflictException.Reason.CLIENT_REVOKED, exception.reason());
        verify(clients, never()).createWithId(any(), any(), any());
    }

    private static OAuthClientBootstrapState state(ReservedServiceClientBootstrapInput input) {
        OAuthClient client = OAuthClient.register(
                        input.service().displayName(), input.service().allowedScopes(), NOW.minusSeconds(10))
                .identifiedBy(input.clientId());
        return new OAuthClientBootstrapState(client, List.of(new OAuthClientBootstrapState.SecretState(
                ClientSecretDigest.fromPlaintext(input.clientSecret()), null, null)));
    }

    private static List<ReservedServiceClientBootstrapInput> inputs() {
        return List.of(
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.IAM,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f"), IAM_SECRET),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.TENANT_ACCESS,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90"), TENANT_ACCESS_SECRET),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.ENTITLEMENT,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91"), ENTITLEMENT_SECRET));
    }

    private static String secret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

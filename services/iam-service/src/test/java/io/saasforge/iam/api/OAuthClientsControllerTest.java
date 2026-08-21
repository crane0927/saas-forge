package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.contract.model.CreateOAuthClientRequest;
import io.saasforge.iam.contract.model.RuntimeScope;
import io.saasforge.iam.domain.client.ClientSecret;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OAuthClientsControllerTest {

    @Test
    void createsRotatesAndRevokesClientWithoutReturningAStoredSecret() {
        InMemoryOAuthClientRepository repository = new InMemoryOAuthClientRepository();
        OAuthClientsController controller = new OAuthClientsController(repository);
        CreateOAuthClientRequest request = new CreateOAuthClientRequest(
                "reporting-worker", Set.of(RuntimeScope.RUNTIME_READ, RuntimeScope.RUNTIME_QUOTA_WRITE));

        var created = controller.createOAuthClient(UUID.randomUUID(), request);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("reporting-worker", created.getBody().getDisplayName());
        assertEquals(43, created.getBody().getClientSecret().length());
        assertEquals(
                "/api/v1/platform/oauth-clients/" + repository.client.id(),
                created.getHeaders().getLocation().toString());
        assertNotNull(repository.initialDigest);

        var rotated = controller.rotateOAuthClientSecret(repository.client.id(), UUID.randomUUID());

        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        assertNotNull(rotated.getBody().getUpdatedAt());
        assertFalse(created.getBody().getClientSecret().equals(rotated.getBody().getClientSecret()));

        var revoked = controller.revokeOAuthClient(repository.client.id(), UUID.randomUUID());

        assertEquals(HttpStatus.NO_CONTENT, revoked.getStatusCode());
        assertTrue(repository.revoked);
    }

    @Test
    void mapsDomainFailuresToPublicHttpStatuses() {
        OAuthClientsController invalidRequestController = new OAuthClientsController(new InMemoryOAuthClientRepository());
        CreateOAuthClientRequest invalid = new CreateOAuthClientRequest("", Set.of(RuntimeScope.RUNTIME_READ));

        ResponseStatusException badRequest = assertThrows(
                ResponseStatusException.class,
                () -> invalidRequestController.createOAuthClient(UUID.randomUUID(), invalid));
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());

        OAuthClientsController missingController = new OAuthClientsController(new FailingOAuthClientRepository());
        ResponseStatusException notFound = assertThrows(
                ResponseStatusException.class,
                () -> missingController.revokeOAuthClient(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> missingController.rotateOAuthClientSecret(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @Test
    void publicClientContractCannotExpressReservedInternalScopes() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeScope.fromValue("iam:identity:write"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeScope.fromValue("tenant-access:tenant:read"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeScope.fromValue("entitlement:quota:write"));
    }

    private static final class InMemoryOAuthClientRepository implements OAuthClientRepository {

        private OAuthClient client;
        private Sha256Digest initialDigest;
        private Sha256Digest currentDigest;
        private boolean revoked;

        @Override
        public OAuthClient create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            this.client = client.identifiedBy(UUID.randomUUID());
            this.initialDigest = initialSecretDigest;
            this.currentDigest = initialSecretDigest;
            return this.client;
        }

        @Override
        public OAuthClient createWithId(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            return create(client, initialSecretDigest, issuedAt);
        }

        @Override
        public void lockReservedClientBootstrap() {
        }

        @Override
        public Optional<OAuthClientBootstrapState> findBootstrapState(UUID clientId) {
            return Optional.empty();
        }

        @Override
        public Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at) {
            return currentDigest.equals(secretDigest) ? Optional.of(client) : Optional.empty();
        }

        @Override
        public ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at) {
            currentDigest = nextSecretDigest;
            return ClientSecret.issued(clientId, at);
        }

        @Override
        public void revoke(UUID clientId, Instant at) {
            revoked = true;
        }
    }

    private static final class FailingOAuthClientRepository implements OAuthClientRepository {

        @Override
        public OAuthClient create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            throw new IllegalStateException("duplicate");
        }

        @Override
        public OAuthClient createWithId(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            throw new IllegalStateException("duplicate");
        }

        @Override
        public void lockReservedClientBootstrap() {
            throw new IllegalStateException("conflict");
        }

        @Override
        public Optional<OAuthClientBootstrapState> findBootstrapState(UUID clientId) {
            return Optional.empty();
        }

        @Override
        public Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at) {
            return Optional.empty();
        }

        @Override
        public ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at) {
            throw new IllegalStateException("conflict");
        }

        @Override
        public void revoke(UUID clientId, Instant at) {
            throw new IllegalArgumentException("missing");
        }
    }
}

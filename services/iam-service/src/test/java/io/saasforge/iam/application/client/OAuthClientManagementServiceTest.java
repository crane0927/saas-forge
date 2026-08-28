package io.saasforge.iam.application.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.client.ClientSecret;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientManagementOperation;
import io.saasforge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientScopeGrantForbiddenException;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OAuthClientManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T01:02:03Z");
    private static final UUID ACTOR = UUID.fromString("0198f240-0000-7000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("0198f240-0000-7000-8000-000000000002");
    private static final UUID CLIENT = UUID.fromString("0198f240-0000-7000-8000-000000000003");

    private final InMemoryClients clients = new InMemoryClients();
    private final InMemoryOperations operations = new InMemoryOperations();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private OAuthClientManagementService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        service = new OAuthClientManagementService(
                clients, operations, outbox,
                new OAuthClientCreatedEventFactory(new ObjectMapper(), ids, "test"),
                new ClientSecretIssuer(new SecureRandom()), ids, clock);
    }

    @Test
    void createsRuntimeClientAndPersistsOnlyNonSensitiveTerminalFacts() {
        var result = service.create(ACTOR, KEY, "reporting-worker",
                Set.of(OAuthScope.RUNTIME_READ, OAuthScope.RUNTIME_QUOTA_WRITE), null);

        assertEquals(CLIENT, result.client().id());
        assertEquals(43, result.clientSecret().length());
        assertNotNull(clients.digest);
        assertEquals("RUNTIME_SERVICE", result.client().clientType().name());
        assertEquals(NOW, result.client().updatedAt());
        assertEquals(CLIENT, operations.operation.clientId());
        assertEquals(201, operations.operation.httpStatus());
        assertNotNull(outbox.event);
        assertEquals(CLIENT.toString(), outbox.event.orderingKey());
        assertTrue(outbox.event.eventSnapshot().contains("OAUTH_CLIENT_CREATED"));
        assertFalse(outbox.event.eventSnapshot().contains(result.clientSecret()));
        assertFalse(outbox.event.eventSnapshot().toLowerCase().contains("secret"));
    }

    @Test
    void neverReplaysSecretAndKeepsKeyBoundToTheOriginalFingerprint() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);

        OAuthClientManagementException replay = assertThrows(OAuthClientManagementException.class,
                () -> service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null));
        assertEquals("CLIENT_SECRET_ALREADY_REVEALED", replay.code());

        OAuthClientManagementException conflict = assertThrows(OAuthClientManagementException.class,
                () -> service.create(ACTOR, KEY, "other", Set.of(OAuthScope.RUNTIME_READ), null));
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
    }

    @Test
    void rejectsInvalidKeysConcurrentWorkAndInternalScopeGrants() {
        OAuthClientManagementException invalid = assertThrows(OAuthClientManagementException.class,
                () -> service.create(ACTOR, UUID.randomUUID(), "worker", Set.of(OAuthScope.RUNTIME_READ), null));
        assertEquals("IDEMPOTENCY_KEY_INVALID", invalid.code());

        operations.lockAvailable = false;
        OAuthClientManagementException pending = assertThrows(OAuthClientManagementException.class,
                () -> service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null));
        assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", pending.code());

        operations.lockAvailable = true;
        assertThrows(OAuthClientScopeGrantForbiddenException.class,
                () -> service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.IAM_IDENTITY_WRITE), null));
    }

    @Test
    void readsOnlyTheClientAggregateAndReportsMissingClient() {
        OAuthClient created = service.create(
                ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null).client();
        assertEquals(created, service.get(created.id()));
        OAuthClientManagementException missing = assertThrows(
                OAuthClientManagementException.class, () -> service.get(UUID.randomUUID()));
        assertEquals("OAUTH_CLIENT_NOT_FOUND", missing.code());
    }

    private static final class InMemoryClients implements OAuthClientRepository {
        private OAuthClient client;
        private Sha256Digest digest;

        @Override
        public OAuthClient create(OAuthClient value, Sha256Digest initialSecretDigest, Instant issuedAt) {
            client = value.identifiedBy(CLIENT);
            digest = initialSecretDigest;
            return client;
        }
        @Override public OAuthClient createWithId(OAuthClient value, Sha256Digest digest, Instant at) {
            return create(value, digest, at);
        }
        @Override public void lockReservedClientBootstrap() { }
        @Override public Optional<OAuthClientBootstrapState> findBootstrapState(UUID id) { return Optional.empty(); }
        @Override public Optional<OAuthClient> findById(UUID id) {
            return Optional.ofNullable(client).filter(value -> value.id().equals(id));
        }
        @Override public Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest digest, Instant at) {
            return Optional.empty();
        }
        @Override public ClientSecret rotate(UUID id, Sha256Digest digest, Instant at) {
            throw new UnsupportedOperationException();
        }
        @Override public void revoke(UUID id, Instant at) { throw new UnsupportedOperationException(); }
    }

    private static final class InMemoryOperations implements OAuthClientManagementOperationRepository {
        private boolean lockAvailable = true;
        private OAuthClientManagementOperation operation;
        @Override public boolean tryLock(UUID actor, UUID key) { return lockAvailable; }
        @Override public Optional<OAuthClientManagementOperation> find(UUID actor, UUID key) {
            return Optional.ofNullable(operation)
                    .filter(value -> value.actorIdentityId().equals(actor) && value.idempotencyKey().equals(key));
        }
        @Override public void append(OAuthClientManagementOperation value) { operation = value; }
    }

    private static final class InMemoryOutbox implements OutboxEventRepository {
        private OutboxEvent event;
        @Override public void append(OutboxEvent value) { event = value; }
        @Override public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant until) {
            return Optional.empty();
        }
        @Override public void markPublished(ClaimedOutboxEvent event, Instant at) { }
        @Override public void releaseAfterFailure(ClaimedOutboxEvent event, Instant at, String summary) { }
    }
}

package io.saasforge.iam.application.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.domain.client.ClientSecret;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientManagementOperation;
import io.saasforge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saasforge.iam.domain.client.OAuthClientCreation;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientScopeGrantForbiddenException;
import io.saasforge.iam.domain.client.OAuthClientSecretRotationException;
import io.saasforge.iam.domain.client.OAuthClientStatus;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OAuthClientManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T01:02:03Z");
    private static final UUID ACTOR = UUID.fromString("0198f240-0000-7000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("0198f240-0000-7000-8000-000000000002");
    private static final UUID CLIENT = UUID.fromString("0198f240-0000-7000-8000-000000000003");
    private static final UUID ROTATION_KEY = UUID.fromString("0198f240-0000-7000-8000-000000000004");
    private static final UUID RECOVERY_KEY = UUID.fromString("0198f240-0000-7000-8000-000000000009");
    private static final UUID SECOND_RECOVERY_KEY = UUID.fromString("0198f240-0000-7000-8000-00000000000a");
    private static final UUID INITIAL_SECRET_ID = UUID.fromString("0198f240-0000-7000-8000-00000000000b");

    private final InMemoryClients clients = new InMemoryClients();
    private final InMemoryOperations operations = new InMemoryOperations();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final RevocationIndex revocations = org.mockito.Mockito.mock(RevocationIndex.class);
    private OAuthClientManagementService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        service = new OAuthClientManagementService(
                clients, operations, outbox,
                new OAuthClientCreatedEventFactory(new ObjectMapper(), ids, "test"),
                new ClientSecretRotatedEventFactory(new ObjectMapper(), ids, "test"),
                new ClientSecretIssuanceRecoveredEventFactory(new ObjectMapper(), ids, "test"),
                new OAuthClientRevokedEventFactory(new ObjectMapper(), ids, "test"),
                revocations,
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
        assertEquals(CLIENT, replay.clientId());

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

    @Test
    void rotatesOnceAndPersistsOnlyNonSensitiveTerminalFacts() {
        String initialSecret = service.create(
                ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null).clientSecret();

        var result = service.rotate(ACTOR, ROTATION_KEY, CLIENT, "0123456789abcdef0123456789abcdef");

        assertEquals(CLIENT, result.client().id());
        assertEquals(43, result.clientSecret().length());
        assertFalse(initialSecret.equals(result.clientSecret()));
        assertEquals(NOW, result.client().updatedAt());
        assertEquals("ROTATE", operations.operation.operationType());
        assertEquals(200, operations.operation.httpStatus());
        assertTrue(outbox.event.eventSnapshot().contains("CLIENT_SECRET_ROTATED"));
        assertFalse(outbox.event.eventSnapshot().contains(result.clientSecret()));
        assertFalse(outbox.event.eventSnapshot().toLowerCase().contains("digest"));
    }

    @Test
    void rotationNeverReplaysSecretAndBindsTheKeyToOneClient() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);
        service.rotate(ACTOR, ROTATION_KEY, CLIENT, null);

        OAuthClientManagementException replay = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY, CLIENT, null));
        assertEquals("CLIENT_SECRET_ALREADY_REVEALED", replay.code());

        OAuthClientManagementException conflict = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY,
                        UUID.fromString("0198f240-0000-7000-8000-000000000005"), null));
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
    }

    @Test
    void rotationMapsMissingRevokedOverlapAndConcurrentFailures() {
        OAuthClientManagementException missing = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY, CLIENT, null));
        assertEquals("OAUTH_CLIENT_NOT_FOUND", missing.code());

        clients.client = OAuthClient.restore(CLIENT, "worker", Set.of(OAuthScope.RUNTIME_READ),
                OAuthClientStatus.REVOKED, NOW.minusSeconds(1), NOW.minusSeconds(1));
        OAuthClientManagementException revoked = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY, CLIENT, null));
        assertEquals("OAUTH_CLIENT_REVOKED", revoked.code());

        clients.client = OAuthClient.restore(CLIENT, "worker", Set.of(OAuthScope.RUNTIME_READ),
                OAuthClientStatus.ACTIVE, NOW.minusSeconds(1), null);
        clients.overlapActive = true;
        OAuthClientManagementException overlap = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY, CLIENT, null));
        assertEquals("CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE", overlap.code());

        clients.overlapActive = false;
        operations.lockAvailable = false;
        OAuthClientManagementException pending = assertThrows(OAuthClientManagementException.class,
                () -> service.rotate(ACTOR, ROTATION_KEY, CLIENT, null));
        assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", pending.code());
    }

    @Test
    void recoversCreationOnceForTheOriginalActorAndClientWithoutPersistingSecret() {
        String initial = service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null).clientSecret();

        var recovered = service.recover(ACTOR, RECOVERY_KEY, CLIENT, KEY, null);

        assertEquals(43, recovered.clientSecret().length());
        assertFalse(initial.equals(recovered.clientSecret()));
        assertEquals(INITIAL_SECRET_ID, clients.recoveredOriginalSecretId);
        assertEquals("RECOVER", operations.operation.operationType());
        assertEquals(operations.stored.get(ACTOR + ":" + KEY).id(), operations.operation.originalOperationId());
        assertTrue(outbox.event.eventSnapshot().contains("CLIENT_SECRET_ISSUANCE_RECOVERED"));
        assertTrue(outbox.event.eventSnapshot().contains("originalOperationId"));
        assertFalse(outbox.event.eventSnapshot().contains(recovered.clientSecret()));

        assertRecoveryRejected(SECOND_RECOVERY_KEY, ACTOR, CLIENT, KEY);
        assertRecoveryRejected(RECOVERY_KEY, ACTOR, CLIENT, KEY);
    }

    @Test
    void rejectsRecoveryForAnotherActorClientInvalidOriginalKeyAndExpiredWindow() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);

        assertRecoveryRejected(RECOVERY_KEY,
                UUID.fromString("0198f240-0000-7000-8000-00000000000c"), CLIENT, KEY);
        assertRecoveryRejected(RECOVERY_KEY, ACTOR,
                UUID.fromString("0198f240-0000-7000-8000-00000000000d"), KEY);
        OAuthClientManagementException invalid = assertThrows(OAuthClientManagementException.class,
                () -> service.recover(ACTOR, RECOVERY_KEY, CLIENT, UUID.randomUUID(), null));
        assertEquals("CLIENT_SECRET_RECOVERY_REQUEST_INVALID", invalid.code());

        Clock expired = Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(expired, new SecureRandom());
        service = managementService(ids, expired);
        assertRecoveryRejected(RECOVERY_KEY, ACTOR, CLIENT, KEY);
    }

    @Test
    void allowsRecoveryAtTheExactTenMinuteBoundaryAndRejectsKeyFingerprintConflict() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);
        Clock boundary = Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(boundary, new SecureRandom());
        service = managementService(ids, boundary);

        service.recover(ACTOR, RECOVERY_KEY, CLIENT, KEY, null);
        OAuthClientManagementException conflict = assertThrows(OAuthClientManagementException.class,
                () -> service.recover(ACTOR, RECOVERY_KEY, CLIENT, ROTATION_KEY, null));
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.code());
    }

    @Test
    void rejectsConcurrentRecoveryWhileTheOriginalOperationIsLocked() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);
        operations.recoveryLockAvailable = false;

        OAuthClientManagementException concurrent = assertThrows(OAuthClientManagementException.class,
                () -> service.recover(ACTOR, RECOVERY_KEY, CLIENT, KEY, null));

        assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", concurrent.code());
        assertEquals(null, clients.recoveredOriginalSecretId);
    }

    @Test
    void revokesOnceAfterRedisAndPreservesTheFirstTerminalFacts() {
        service.create(ACTOR, KEY, "worker", Set.of(OAuthScope.RUNTIME_READ), null);

        service.revoke(ACTOR, ROTATION_KEY, CLIENT, null);

        org.mockito.Mockito.verify(revocations).revokeClient(CLIENT);
        assertEquals(OAuthClientStatus.REVOKED, clients.client.status());
        assertEquals("REVOKE", operations.operation.operationType());
        assertEquals(204, operations.operation.httpStatus());
        assertTrue(outbox.event.eventSnapshot().contains("OAUTH_CLIENT_REVOKED"));
        OAuthClientManagementOperation first = operations.operation;
        OutboxEvent firstEvent = outbox.event;

        service.revoke(UUID.fromString("0198f240-0000-7000-8000-000000000007"),
                UUID.fromString("0198f240-0000-7000-8000-000000000008"), CLIENT, null);
        assertEquals(first, operations.operation);
        assertEquals(firstEvent, outbox.event);
    }

    private static final class InMemoryClients implements OAuthClientRepository {
        private OAuthClient client;
        private Sha256Digest digest;
        private boolean overlapActive;
        private UUID recoveredOriginalSecretId;

        @Override
        public OAuthClientCreation create(OAuthClient value, Sha256Digest initialSecretDigest, Instant issuedAt) {
            client = value.identifiedBy(CLIENT);
            digest = initialSecretDigest;
            return new OAuthClientCreation(client,
                    ClientSecret.issued(CLIENT, issuedAt).identifiedBy(INITIAL_SECRET_ID));
        }
        @Override public OAuthClient createWithId(OAuthClient value, Sha256Digest digest, Instant at) {
            return create(value, digest, at).client();
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
            if (client == null || !client.id().equals(id)) {
                throw new OAuthClientSecretRotationException(
                        OAuthClientSecretRotationException.Reason.CLIENT_NOT_FOUND, "not found");
            }
            if (client.status() == OAuthClientStatus.REVOKED) {
                throw new OAuthClientSecretRotationException(
                        OAuthClientSecretRotationException.Reason.CLIENT_REVOKED, "revoked");
            }
            if (overlapActive) {
                throw new OAuthClientSecretRotationException(
                        OAuthClientSecretRotationException.Reason.OVERLAP_ACTIVE, "overlap");
            }
            overlapActive = true;
            this.digest = digest;
            client = OAuthClient.restore(client.id(), client.displayName(), client.clientType(),
                    client.reservedServiceKey(), client.allowedScopes(), client.status(),
                    client.createdAt(), at, client.revokedAt());
            return ClientSecret.issued(id, at)
                    .identifiedBy(UUID.fromString("0198f240-0000-7000-8000-000000000006"));
        }
        @Override public ClientSecret recover(UUID id, UUID originalSecretId, Sha256Digest digest, Instant at) {
            if (client == null || !client.id().equals(id) || recoveredOriginalSecretId != null) {
                throw new io.saasforge.iam.domain.client.OAuthClientSecretRecoveryException(
                        io.saasforge.iam.domain.client.OAuthClientSecretRecoveryException.Reason.SECRET_NOT_RECOVERABLE,
                        "not recoverable");
            }
            recoveredOriginalSecretId = originalSecretId;
            this.digest = digest;
            client = OAuthClient.restore(client.id(), client.displayName(), client.clientType(),
                    client.reservedServiceKey(), client.allowedScopes(), client.status(),
                    client.createdAt(), at, client.revokedAt());
            return ClientSecret.issued(id, at)
                    .identifiedBy(UUID.fromString("0198f240-0000-7000-8000-00000000000e"));
        }
        @Override public boolean revoke(UUID id, Instant at) {
            if (client == null || !client.id().equals(id)) throw new IllegalArgumentException("not found");
            if (client.status() == OAuthClientStatus.REVOKED) return false;
            client = client.revoke(at);
            return true;
        }
        @Override public List<UUID> findRevokedClientIds() {
            return client != null && client.status() == OAuthClientStatus.REVOKED
                    ? List.of(client.id()) : List.of();
        }
    }

    private static final class InMemoryOperations implements OAuthClientManagementOperationRepository {
        private boolean lockAvailable = true;
        private boolean recoveryLockAvailable = true;
        private OAuthClientManagementOperation operation;
        private final Map<String, OAuthClientManagementOperation> stored = new HashMap<>();
        @Override public boolean tryLock(UUID actor, UUID key) { return lockAvailable; }
        @Override public Optional<OAuthClientManagementOperation> find(UUID actor, UUID key) {
            return Optional.ofNullable(stored.get(actor + ":" + key));
        }
        @Override public boolean tryLockRecovery(UUID originalId) { return recoveryLockAvailable; }
        @Override public Optional<OAuthClientManagementOperation> findSuccessfulRecovery(UUID originalId) {
            return stored.values().stream().filter(value -> "RECOVER".equals(value.operationType())
                    && originalId.equals(value.originalOperationId())).findFirst();
        }
        @Override public void append(OAuthClientManagementOperation value) {
            operation = value;
            stored.put(value.actorIdentityId() + ":" + value.idempotencyKey(), value);
        }
    }

    private OAuthClientManagementService managementService(UuidV7Generator ids, Clock clock) {
        return new OAuthClientManagementService(
                clients, operations, outbox,
                new OAuthClientCreatedEventFactory(new ObjectMapper(), ids, "test"),
                new ClientSecretRotatedEventFactory(new ObjectMapper(), ids, "test"),
                new ClientSecretIssuanceRecoveredEventFactory(new ObjectMapper(), ids, "test"),
                new OAuthClientRevokedEventFactory(new ObjectMapper(), ids, "test"),
                revocations, new ClientSecretIssuer(new SecureRandom()), ids, clock);
    }

    private void assertRecoveryRejected(UUID key, UUID actor, UUID clientId, UUID originalKey) {
        OAuthClientManagementException exception = assertThrows(OAuthClientManagementException.class,
                () -> service.recover(actor, key, clientId, originalKey, null));
        assertEquals("CLIENT_SECRET_RECOVERY_NOT_ALLOWED", exception.code());
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

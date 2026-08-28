package io.saasforge.iam.application.client;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientManagementOperation;
import io.saasforge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class OAuthClientManagementService {
    private final OAuthClientRepository clients;
    private final OAuthClientManagementOperationRepository operations;
    private final OutboxEventRepository outbox;
    private final OAuthClientCreatedEventFactory events;
    private final ClientSecretIssuer secrets;
    private final UuidV7Generator ids;
    private final Clock clock;

    public OAuthClientManagementService(
            OAuthClientRepository clients,
            OAuthClientManagementOperationRepository operations,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory events,
            ClientSecretIssuer secrets,
            UuidV7Generator ids,
            Clock clock) {
        this.clients = clients;
        this.operations = operations;
        this.outbox = outbox;
        this.events = events;
        this.secrets = secrets;
        this.ids = ids;
        this.clock = clock;
    }

    /** Client、Secret 摘要、永久操作终态与事件必须在同一事务提交后才展示 Secret。 */
    @Transactional
    public OAuthClientSecretResult create(
            UUID actorIdentityId,
            UUID idempotencyKey,
            String displayName,
            Set<OAuthScope> allowedScopes,
            String traceId) {
        requireUuidV7(idempotencyKey);
        Sha256Digest fingerprint = fingerprint(displayName, allowedScopes);
        if (!operations.tryLock(actorIdentityId, idempotencyKey)) {
            throw OAuthClientManagementException.inProgress();
        }
        operations.find(actorIdentityId, idempotencyKey).ifPresent(existing -> {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw OAuthClientManagementException.keyReused();
            }
            throw OAuthClientManagementException.secretAlreadyRevealed();
        });

        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        OAuthClient prepared = OAuthClient.registerRuntime(displayName, allowedScopes, createdAt);
        ClientSecretIssuer.IssuedClientSecret issued = secrets.issue();
        OAuthClient client = clients.create(prepared, issued.digest(), createdAt);
        OAuthClientManagementOperation operation = new OAuthClientManagementOperation(
                ids.next(), actorIdentityId, idempotencyKey, "CREATE", client.id(), fingerprint,
                "SUCCEEDED", 201, createdAt);
        operations.append(operation);
        outbox.append(events.create(client, operation, actorIdentityId, createdAt, traceId));
        return new OAuthClientSecretResult(client, issued.plaintext());
    }

    @Transactional(readOnly = true)
    public OAuthClient get(UUID clientId) {
        return clients.findById(clientId).orElseThrow(OAuthClientManagementException::notFound);
    }

    private static void requireUuidV7(UUID key) {
        if (key == null || key.version() != 7) throw OAuthClientManagementException.idempotencyInvalid();
    }

    private static Sha256Digest fingerprint(String displayName, Set<OAuthScope> scopes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, displayName == null ? "" : displayName);
            if (scopes != null) {
                scopes.stream().map(OAuthScope::value).sorted(Comparator.naturalOrder())
                        .forEach(value -> update(digest, value));
            }
            return Sha256Digest.of(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public record OAuthClientSecretResult(OAuthClient client, String clientSecret) {
    }
}

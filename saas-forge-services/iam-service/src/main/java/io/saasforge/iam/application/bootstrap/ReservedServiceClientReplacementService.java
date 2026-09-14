package io.saasforge.iam.application.bootstrap;

import io.saasforge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.OAuthClientType;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacement;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacementRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class ReservedServiceClientReplacementService {
    private final OAuthClientRepository clients;
    private final ReservedServiceClientReplacementRepository replacements;
    private final OutboxEventRepository outbox;
    private final OAuthClientCreatedEventFactory events;
    private final Clock clock;

    public ReservedServiceClientReplacementService(
            OAuthClientRepository clients,
            ReservedServiceClientReplacementRepository replacements,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory events,
            Clock clock) {
        this.clients = clients;
        this.replacements = replacements;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    /** 请求终态、替代 Client、Secret 摘要与 created 事件必须在同一事务提交。 */
    @Transactional
    public ReservedServiceClientReplacementResult replace(
            ReservedServiceClientReplacementInput input, String traceId) {
        Sha256Digest secretDigest = ClientSecretDigest.fromPlaintext(input.newClientSecret());
        Sha256Digest fingerprint = fingerprint(input, secretDigest);
        clients.lockReservedClientBootstrap();

        var existing = replacements.find(input.replacementRequestId());
        if (existing.isPresent()) {
            ReservedServiceClientReplacement replacement = existing.get();
            if (replacement.serviceKey() != input.service().serviceKey()
                    || !replacement.oldClientId().equals(input.oldClientId())
                    || !replacement.newClientId().equals(input.newClientId())
                    || !replacement.requestFingerprint().equals(fingerprint)) {
                throw new ReservedServiceClientReplacementException(
                        ReservedServiceClientReplacementException.Reason.REQUEST_CONFLICT);
            }
            return new ReservedServiceClientReplacementResult(
                    replacement.newClientId(), ReservedServiceClientReplacementResult.Outcome.ALREADY_REPLACED);
        }

        OAuthClient oldClient = clients.findBootstrapState(input.oldClientId())
                .map(state -> state.client())
                .orElseThrow(() -> new ReservedServiceClientReplacementException(
                        ReservedServiceClientReplacementException.Reason.OLD_CLIENT_NOT_FOUND));
        if (oldClient.clientType() != OAuthClientType.RESERVED_SERVICE
                || oldClient.reservedServiceKey() != input.service().serviceKey()) {
            throw new ReservedServiceClientReplacementException(
                    ReservedServiceClientReplacementException.Reason.SERVICE_MISMATCH);
        }
        if (oldClient.status() != OAuthClientStatus.REVOKED) {
            throw new ReservedServiceClientReplacementException(
                    ReservedServiceClientReplacementException.Reason.OLD_CLIENT_NOT_REVOKED);
        }
        if (clients.findById(input.newClientId()).isPresent()) {
            throw new ReservedServiceClientReplacementException(
                    ReservedServiceClientReplacementException.Reason.NEW_CLIENT_ID_USED);
        }
        if (clients.findActiveByReservedServiceKey(input.service().serviceKey()).isPresent()) {
            throw new ReservedServiceClientReplacementException(
                    ReservedServiceClientReplacementException.Reason.ACTIVE_CLIENT_EXISTS);
        }

        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        OAuthClient newClient = OAuthClient.register(
                        input.service().displayName(), input.service().allowedScopes(), completedAt)
                .identifiedBy(input.newClientId());
        clients.createWithId(newClient, secretDigest, completedAt);
        ReservedServiceClientReplacement replacement = new ReservedServiceClientReplacement(
                input.replacementRequestId(), input.service().serviceKey(), input.oldClientId(),
                input.newClientId(), fingerprint, completedAt);
        replacements.append(replacement);
        outbox.append(events.createForDeployment(
                newClient, input.replacementRequestId(), completedAt, traceId));
        return new ReservedServiceClientReplacementResult(
                input.newClientId(), ReservedServiceClientReplacementResult.Outcome.REPLACED);
    }

    private static Sha256Digest fingerprint(
            ReservedServiceClientReplacementInput input, Sha256Digest secretDigest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, input.service().serviceKey().name());
            update(digest, input.oldClientId().toString());
            update(digest, input.newClientId().toString());
            // 二次摘要既绑定 Secret 变化，又避免幂等表持有可直接用于认证查询的 Secret 摘要。
            digest.update(secretDigest.value());
            return Sha256Digest.of(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

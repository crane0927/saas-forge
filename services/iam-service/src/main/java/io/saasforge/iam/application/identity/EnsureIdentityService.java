package io.saasforge.iam.application.identity;

import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityCredentialStatus;
import io.saasforge.iam.domain.identity.IdentityProvisioningFact;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class EnsureIdentityService {
    private final IdentityProvisioningRepository requests;
    private final IdentityRepository identities;
    private final Clock clock;

    public EnsureIdentityService(
            IdentityProvisioningRepository requests,
            IdentityRepository identities,
            Clock clock) {
        this.requests = requests;
        this.identities = identities;
        this.clock = clock;
    }

    /** Identity、凭证结论和幂等事实必须共享事务；失败不得留下无事实可追踪的 Identity。 */
    @Transactional
    public EnsureIdentityResult ensure(
            UUID callerClientId,
            UUID requestId,
            String email,
            String displayName) {
        requireUuidV7(callerClientId, "callerClientId");
        requireUuidV7(requestId, "requestId");
        NormalizedEmail normalizedEmail = NormalizedEmail.from(email);
        Instant ensuredAt = now();
        Identity candidate = Identity.register(normalizedEmail.value(), displayName, ensuredAt);
        Sha256Digest fingerprint = fingerprint(normalizedEmail, displayName);

        requests.lockRequest(callerClientId, requestId);
        var replay = requests.find(callerClientId, requestId);
        if (replay.isPresent()) {
            IdentityProvisioningFact fact = replay.orElseThrow();
            if (!fact.requestFingerprint().equals(fingerprint)) {
                throw new EnsureIdentityRequestConflictException();
            }
            return new EnsureIdentityResult(fact.identityId(), fact.credentialStatus());
        }

        Identity identity = identities.findOrCreate(candidate);
        IdentityCredentialStatus status = credentialStatus(identities.findCredentials(identity.id()), ensuredAt);
        requests.create(new IdentityProvisioningFact(
                callerClientId, requestId, fingerprint, identity.id(), status, ensuredAt));
        return new EnsureIdentityResult(identity.id(), status);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static IdentityCredentialStatus credentialStatus(List<PasswordCredential> credentials, Instant at) {
        if (credentials.stream().anyMatch(credential ->
                credential.type() == CredentialType.PASSWORD && credential.isValidAt(at))) {
            return IdentityCredentialStatus.PASSWORD_READY;
        }
        return credentials.isEmpty()
                ? IdentityCredentialStatus.SETUP_ALLOWED
                : IdentityCredentialStatus.RECOVERY_REQUIRED;
    }

    private static Sha256Digest fingerprint(NormalizedEmail email, String displayName) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
        update(digest, email.value());
        digest.update((byte) (displayName == null ? 0 : 1));
        if (displayName != null) {
            update(digest, displayName);
        }
        return Sha256Digest.of(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}

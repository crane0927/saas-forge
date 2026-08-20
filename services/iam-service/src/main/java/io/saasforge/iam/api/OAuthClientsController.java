package io.saasforge.iam.api;

import io.saasforge.iam.contract.api.OAuthClientsApi;
import io.saasforge.iam.contract.model.CreateOAuthClientRequest;
import io.saasforge.iam.contract.model.OAuthClientSecretResult;
import io.saasforge.iam.contract.model.OAuthClientStatus;
import io.saasforge.iam.contract.model.RuntimeScope;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OAuthClientsController implements OAuthClientsApi {

    private static final int CLIENT_SECRET_BYTES = 32;

    private final OAuthClientRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthClientsController(OAuthClientRepository repository) {
        this.repository = repository;
    }

    @Override
    public ResponseEntity<OAuthClientSecretResult> createOAuthClient(
            UUID idempotencyKey,
            CreateOAuthClientRequest createOAuthClientRequest) {
        Instant now = Instant.now();
        try {
            OAuthClient prepared = OAuthClient.register(
                    createOAuthClientRequest.getDisplayName(),
                    toDomainScopes(createOAuthClientRequest.getAllowedScopes()),
                    now);

            ClientSecretMaterial clientSecret = issueClientSecret();
            OAuthClient created = repository.create(prepared, clientSecret.digest(), now);

            return ResponseEntity.created(locationFor(created.id()))
                    .body(toSecretResult(created, clientSecret.value(), now));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw toConflict(ex);
        }
    }

    @Override
    public ResponseEntity<OAuthClientSecretResult> rotateOAuthClientSecret(UUID clientId, UUID idempotencyKey) {
        Instant now = Instant.now();
        ClientSecretMaterial clientSecret = issueClientSecret();
        try {
            repository.rotate(clientId, clientSecret.digest(), now);
            OAuthClient rotated = repository.findActiveBySecretDigest(clientSecret.digest(), now)
                    .orElseThrow(() -> new IllegalStateException("OAuth Client 轮换后查询失败"));

            return ResponseEntity.ok(toSecretResult(rotated, clientSecret.value(), now));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw toConflict(ex);
        }
    }

    @Override
    public ResponseEntity<Void> revokeOAuthClient(UUID clientId, UUID idempotencyKey) {
        try {
            repository.revoke(clientId, Instant.now());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw toConflict(ex);
        }
    }

    private static URI locationFor(UUID clientId) {
        return URI.create(PATH_CREATE_O_AUTH_CLIENT + "/" + clientId);
    }

    private static ResponseStatusException toConflict(IllegalStateException ex) {
        return new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    }

    private static OAuthClientSecretResult toSecretResult(
            OAuthClient client,
            String clientSecret,
            Instant updatedAt) {
        return new OAuthClientSecretResult()
                .clientId(client.id())
                .displayName(client.displayName())
                .allowedScopes(client.allowedScopes().stream()
                        .map(scope -> RuntimeScope.valueOf(scope.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .status(OAuthClientStatus.valueOf(client.status().name()))
                .createdAt(toOffsetDateTime(client.createdAt()))
                .updatedAt(toOffsetDateTime(updatedAt))
                .clientSecret(clientSecret);
    }

    private static Set<OAuthScope> toDomainScopes(Set<RuntimeScope> scopes) {
        return scopes.stream()
                .map(scope -> OAuthScope.valueOf(scope.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ClientSecretMaterial issueClientSecret() {
        byte[] plainSecretBytes = randomSecretBytes();
        String plainSecret = secureBase64(plainSecretBytes);
        return new ClientSecretMaterial(plainSecret, sha256Digest(plainSecret));
    }

    private byte[] randomSecretBytes() {
        byte[] bytes = new byte[CLIENT_SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private static String secureBase64(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Sha256Digest sha256Digest(String clientSecret) {
        try {
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256")
                    .digest(clientSecret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", ex);
        }
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private record ClientSecretMaterial(String value, Sha256Digest digest) {
    }
}

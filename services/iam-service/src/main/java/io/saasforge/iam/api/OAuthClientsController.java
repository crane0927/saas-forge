package io.saasforge.iam.api;

import io.saasforge.iam.application.client.ClientSecretIssuer;
import io.saasforge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saasforge.iam.application.client.OAuthClientManagementService;
import io.saasforge.iam.contract.api.OAuthClientsApi;
import io.saasforge.iam.contract.model.CreateOAuthClientRequest;
import io.saasforge.iam.contract.model.OAuthClientDetail;
import io.saasforge.iam.contract.model.OAuthClientSecretResult;
import io.saasforge.iam.contract.model.OAuthClientStatus;
import io.saasforge.iam.contract.model.OAuthClientType;
import io.saasforge.iam.contract.model.ReservedServiceKey;
import io.saasforge.iam.contract.model.RuntimeScope;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OAuthClientsController implements OAuthClientsApi {
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final OAuthClientManagementAuthorizer authorizer;
    private final OAuthClientManagementService management;
    private final OAuthClientRepository repository;
    private final ClientSecretIssuer secrets;
    private final Clock clock;

    public OAuthClientsController(
            OAuthClientManagementAuthorizer authorizer,
            OAuthClientManagementService management,
            OAuthClientRepository repository,
            ClientSecretIssuer secrets,
            Clock clock) {
        this.authorizer = authorizer;
        this.management = management;
        this.repository = repository;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<OAuthClientSecretResult> createOAuthClient(
            UUID idempotencyKey, CreateOAuthClientRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        var result = management.create(actorIdentityId, idempotencyKey, request.getDisplayName(),
                toDomainScopes(request.getAllowedScopes()), traceId(httpRequest));
        return ResponseEntity.created(locationFor(result.client().id()))
                .cacheControl(CacheControl.noStore())
                .body(toSecretResult(result.client(), result.clientSecret()));
    }

    @Override
    public ResponseEntity<OAuthClientDetail> getOAuthClient(UUID clientId) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toDetail(management.get(clientId)));
    }

    @Override
    public ResponseEntity<OAuthClientSecretResult> rotateOAuthClientSecret(UUID clientId, UUID idempotencyKey) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ClientSecretIssuer.IssuedClientSecret issued = secrets.issue();
        try {
            repository.rotate(clientId, issued.digest(), now);
            OAuthClient rotated = repository.findActiveBySecretDigest(issued.digest(), now)
                    .orElseThrow(() -> new IllegalStateException("OAuth Client 轮换后查询失败"));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(toSecretResult(rotated, issued.plaintext()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @Override
    public ResponseEntity<Void> revokeOAuthClient(UUID clientId, UUID idempotencyKey) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        try {
            repository.revoke(clientId, clock.instant().truncatedTo(ChronoUnit.MILLIS));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Matcher matcher = TRACE_PARENT.matcher(traceparent == null ? "" : traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static URI locationFor(UUID clientId) {
        return URI.create(PATH_CREATE_O_AUTH_CLIENT + "/" + clientId);
    }

    private static OAuthClientSecretResult toSecretResult(OAuthClient client, String clientSecret) {
        return new OAuthClientSecretResult()
                .clientId(client.id())
                .displayName(client.displayName())
                .allowedScopes(toContractScopes(client.allowedScopes()))
                .status(OAuthClientStatus.valueOf(client.status().name()))
                .createdAt(toOffsetDateTime(client.createdAt()))
                .updatedAt(toOffsetDateTime(client.updatedAt()))
                .clientSecret(clientSecret);
    }

    private static OAuthClientDetail toDetail(OAuthClient client) {
        return new OAuthClientDetail()
                .clientId(client.id())
                .displayName(client.displayName())
                .clientType(OAuthClientType.valueOf(client.clientType().name()))
                .reservedServiceKey(client.reservedServiceKey() == null
                        ? null : ReservedServiceKey.valueOf(client.reservedServiceKey().name()))
                .allowedScopes(toContractScopes(client.allowedScopes()))
                .status(OAuthClientStatus.valueOf(client.status().name()))
                .createdAt(toOffsetDateTime(client.createdAt()))
                .updatedAt(toOffsetDateTime(client.updatedAt()))
                .revokedAt(toOffsetDateTime(client.revokedAt()));
    }

    private static Set<RuntimeScope> toContractScopes(Set<OAuthScope> scopes) {
        return scopes.stream().map(scope -> RuntimeScope.fromValue(scope.value()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<OAuthScope> toDomainScopes(Set<RuntimeScope> scopes) {
        return scopes.stream().map(scope -> OAuthScope.fromValue(scope.getValue()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null
                : OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}

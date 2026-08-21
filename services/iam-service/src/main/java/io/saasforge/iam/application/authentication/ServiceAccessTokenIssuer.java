package io.saasforge.iam.application.authentication;

import io.saasforge.iam.application.signing.JwsSigningInput;
import io.saasforge.iam.application.signing.JwtSignature;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.client.OAuthScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** 签发固定声明白名单的 Service Access Token。 */
public final class ServiceAccessTokenIssuer {
    private static final String AUDIENCE = "saasforge-api";

    private final JwtSigningService signingService;
    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    public ServiceAccessTokenIssuer(
            JwtSigningService signingService,
            ObjectMapper objectMapper,
            UuidV7Generator uuidV7Generator,
            Clock clock,
            String issuer,
            Duration ttl) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer 必须显式配置");
        }
        if (ttl == null || ttl.getSeconds() <= 0 || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Service Access Token TTL 必须是整秒正数");
        }
        this.signingService = signingService;
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public IssuedServiceAccessToken issue(UUID clientId, Set<OAuthScope> scopes) {
        if (clientId == null || scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Service Access Token 必须包含 Client 与 Scope");
        }
        String scope = scopes.stream()
                .map(OAuthScope::value)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .collect(Collectors.joining(" "));
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(ttl);
        UUID jti = uuidV7Generator.next();
        String encodedClaims = encodeJson(claims(clientId, scope, jti, issuedAt, expiresAt));
        JwtSignature signature = signingService.sign(ttl, kid -> signingInput(kid, encodedClaims));
        String encodedSigningInput = new String(
                signingInput(signature.kid(), encodedClaims).bytes(), StandardCharsets.US_ASCII);
        String token = encodedSigningInput + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.bytes());
        return new IssuedServiceAccessToken(
                token, jti, signature.kid(), issuedAt, expiresAt, ttl.getSeconds(), scope);
    }

    private JwsSigningInput signingInput(String kid, String encodedClaims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "at+jwt");
        header.put("kid", kid);
        return JwsSigningInput.of((encodeJson(header) + "." + encodedClaims)
                .getBytes(StandardCharsets.US_ASCII));
    }

    private Map<String, Object> claims(
            UUID clientId, String scope, UUID jti, Instant issuedAt, Instant expiresAt) {
        String subject = clientId.toString();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", AUDIENCE);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", jti.toString());
        claims.put("sub", subject);
        claims.put("client_id", subject);
        claims.put("scope", scope);
        return claims;
    }

    private String encodeJson(Map<String, Object> value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
    }
}

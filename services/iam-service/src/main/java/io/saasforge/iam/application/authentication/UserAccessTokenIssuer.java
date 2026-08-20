package io.saasforge.iam.application.authentication;

import io.saasforge.iam.application.signing.JwsSigningInput;
import io.saasforge.iam.application.signing.JwtSignature;
import io.saasforge.iam.application.signing.JwtSigningService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** 签发声明白名单固定的 User Access Token。 */
public final class UserAccessTokenIssuer {
    private static final String AUDIENCE = "saasforge-api";

    private final JwtSigningService signingService;
    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidV7Generator;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    public UserAccessTokenIssuer(
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
            throw new IllegalArgumentException("Access Token TTL 必须是整秒正数");
        }
        this.signingService = signingService;
        this.objectMapper = objectMapper;
        this.uuidV7Generator = uuidV7Generator;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public IssuedAccessToken issueUserToken(UUID identityId, UUID membershipId, UUID tenantId) {
        if ((membershipId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("Membership 与 Tenant 声明必须成对出现");
        }
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(ttl);
        UUID jti = uuidV7Generator.next();
        String encodedClaims = encodeJson(claims(identityId, membershipId, tenantId, jti, issuedAt, expiresAt));
        JwtSignature signature = signingService.sign(kid -> signingInput(kid, encodedClaims));
        String encodedSigningInput = new String(signingInput(signature.kid(), encodedClaims).bytes(), StandardCharsets.US_ASCII);
        String token = encodedSigningInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.bytes());
        return new IssuedAccessToken(token, jti, signature.kid(), issuedAt, expiresAt, ttl.getSeconds());
    }

    private JwsSigningInput signingInput(String kid, String encodedClaims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);
        String value = encodeJson(header) + "." + encodedClaims;
        return JwsSigningInput.of(value.getBytes(StandardCharsets.US_ASCII));
    }

    private Map<String, Object> claims(
            UUID identityId, UUID membershipId, UUID tenantId, UUID jti, Instant issuedAt, Instant expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", AUDIENCE);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("identityId", identityId.toString());
        if (membershipId != null) {
            claims.put("membershipId", membershipId.toString());
            claims.put("tenantId", tenantId.toString());
        }
        claims.put("jti", jti.toString());
        return claims;
    }

    private String encodeJson(Map<String, Object> value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
    }
}

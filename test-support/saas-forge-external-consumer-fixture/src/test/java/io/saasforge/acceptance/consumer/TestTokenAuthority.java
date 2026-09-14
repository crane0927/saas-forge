package io.saasforge.acceptance.consumer;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

final class TestTokenAuthority {

    static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");
    static final String ISSUER = "https://iam.sdk-consumer.test.invalid";
    static final String AUDIENCE = "sdk-external-consumer-fixture";
    static final UUID IDENTITY_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef");
    static final UUID MEMBERSHIP_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdea");
    static final UUID TENANT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdeb");
    static final UUID USER_JTI = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcded");
    static final UUID CLIENT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdec");
    static final UUID SERVICE_JTI = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdee");

    private static final String KID = "sdk-consumer-test-kid";
    private final RSAKey key;

    TestTokenAuthority() {
        try {
            key = new RSAKeyGenerator(2048).keyID(KID).generate();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成消费者验收测试密钥", exception);
        }
    }

    ServiceJwtVerificationKey verificationKey() {
        RSAKey publicKey = key.toPublicJWK();
        return new ServiceJwtVerificationKey(
                KID,
                publicKey.getModulus().toString(),
                publicKey.getPublicExponent().toString());
    }

    String tenantUserToken() {
        return userToken(true);
    }

    String platformUserToken() {
        return userToken(false);
    }

    String serviceToken() {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(SERVICE_JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", "runtime:read")
                .build();
        return sign(new JOSEObjectType("at+jwt"), claims);
    }

    private String userToken(boolean tenantContext) {
        var builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(USER_JTI.toString())
                .claim("identityId", IDENTITY_ID.toString());
        if (tenantContext) {
            builder.claim("membershipId", MEMBERSHIP_ID.toString())
                    .claim("tenantId", TENANT_ID.toString());
        }
        return sign(JOSEObjectType.JWT, builder.build());
    }

    private String sign(JOSEObjectType type, JWTClaimsSet claims) {
        try {
            var token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(type).keyID(KID).build(),
                    claims);
            token.sign(new RSASSASigner(key));
            return token.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("无法签发消费者验收测试 Token", exception);
        }
    }
}

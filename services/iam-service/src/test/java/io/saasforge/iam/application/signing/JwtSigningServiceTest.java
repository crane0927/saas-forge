package io.saasforge.iam.application.signing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.support.StubSigningKeyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtSigningServiceTest {

    @Test
    void signsWithTheOnlyActiveKeyAndRs256() {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        repository.activeKeys(List.of(activeKey("active-kid", "kms/key/7")));
        InMemoryJwtSigningFake signingFake = new InMemoryJwtSigningFake();
        JwtSigningService service = new JwtSigningService(new ActiveSigningKeyResolver(repository), signingFake);

        JwtSignature signature = service.sign(JwsSigningInput.of("header.payload".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("active-kid", signature.kid());
        assertEquals(32, signature.bytes().length);
        assertEquals(1, signingFake.invocationCount());
        assertEquals("kms/key/7", signingFake.keyVersionRef());
        assertEquals(JwtSigningAlgorithm.RS256, signingFake.algorithm());
    }

    @Test
    void mapsAdapterFailureWithoutTryingAnotherKey() {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        repository.activeKeys(List.of(activeKey("active-kid", "kms/key/7")));
        InMemoryJwtSigningFake signingFake = new InMemoryJwtSigningFake();
        IllegalStateException adapterFailure = new IllegalStateException("KMS unavailable");
        signingFake.failWith(adapterFailure);
        JwtSigningService service = new JwtSigningService(new ActiveSigningKeyResolver(repository), signingFake);

        TokenSigningUnavailableException failure = assertThrows(TokenSigningUnavailableException.class,
                () -> service.sign(JwsSigningInput.of(new byte[] {1})));

        assertSame(adapterFailure, failure.getCause());
        assertEquals(1, signingFake.invocationCount());
    }

    @Test
    void rejectsZeroOrMultipleActiveKeysBeforeCallingThePort() {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        InMemoryJwtSigningFake signingFake = new InMemoryJwtSigningFake();
        JwtSigningService service = new JwtSigningService(new ActiveSigningKeyResolver(repository), signingFake);

        repository.activeKeys(List.of());
        assertThrows(IllegalStateException.class,
                () -> service.sign(JwsSigningInput.of(new byte[] {1})));

        repository.activeKeys(List.of(activeKey("kid-1", "kms/key/1"), activeKey("kid-2", "kms/key/2")));
        assertThrows(IllegalStateException.class,
                () -> service.sign(JwsSigningInput.of(new byte[] {1})));
        assertEquals(0, signingFake.invocationCount());
    }

    @Test
    void signingInputAndSignatureDoNotExposeMutableArrays() {
        byte[] source = new byte[] {1, 2, 3};
        JwsSigningInput input = JwsSigningInput.of(source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, input.bytes());

        JwtSignature signature = new JwtSignature("kid", source);
        source[1] = 8;
        assertArrayEquals(new byte[] {9, 2, 3}, signature.bytes());
    }

    private static SigningKey activeKey(String kid, String keyVersionRef) {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        return SigningKey.restore(
                UUID.randomUUID(), kid, keyVersionRef, "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                publishedAt, publishedAt.plusSeconds(300), null, null, null);
    }
}

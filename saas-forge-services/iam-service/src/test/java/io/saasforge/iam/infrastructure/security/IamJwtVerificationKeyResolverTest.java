package io.saasforge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IamJwtVerificationKeyResolverTest {

    @Test
    void resolvesOnlyCurrentlyPublishedVerificationKeys() {
        SigningKeyRepository signingKeys = mock(SigningKeyRepository.class);
        when(signingKeys.findPublishedVerificationKeys()).thenReturn(List.of(SigningKey.publish(
                "key-1", "kms/version/1", "modulus", "AQAB", Instant.parse("2026-08-21T08:00:00Z"))));
        IamJwtVerificationKeyResolver resolver = new IamJwtVerificationKeyResolver(signingKeys);

        var resolved = resolver.findByKid("key-1").orElseThrow();
        assertEquals("key-1", resolved.kid());
        assertEquals("modulus", resolved.modulus());
        assertEquals("AQAB", resolved.exponent());
        assertTrue(resolver.findByKid("absent").isEmpty());
    }
}

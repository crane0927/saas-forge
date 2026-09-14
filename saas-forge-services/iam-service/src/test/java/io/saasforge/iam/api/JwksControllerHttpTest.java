package io.saasforge.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.support.StubSigningKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JwksControllerHttpTest {

    @Test
    void publishesTheVerificationContractAndFiveMinuteCachePolicy() throws Exception {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        repository.publishedVerificationKeys(List.of(
                key("published", SigningKeyStatus.PUBLISHED),
                key("active", SigningKeyStatus.ACTIVE),
                key("retiring", SigningKeyStatus.RETIRING)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new JwksController(repository)).build();

        mockMvc.perform(get("/.well-known/jwks.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "max-age=300"))
                .andExpect(jsonPath("$.keys.length()").value(3))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").value("published"))
                .andExpect(jsonPath("$.keys[0].n").value("modulus-published"))
                .andExpect(jsonPath("$.keys[0].e").value("AQAB"));
    }

    private static SigningKey key(String kid, SigningKeyStatus status) {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        Instant activatedAt = switch (status) {
            case ACTIVE, RETIRING -> publishedAt.plusSeconds(300);
            default -> null;
        };
        Instant retireAfter = status == SigningKeyStatus.RETIRING ? publishedAt.plusSeconds(2_100) : null;
        return SigningKey.restore(
                UUID.randomUUID(), kid, "kms/" + kid, "modulus-" + kid, "AQAB", status,
                publishedAt, activatedAt, retireAfter, null, null);
    }
}

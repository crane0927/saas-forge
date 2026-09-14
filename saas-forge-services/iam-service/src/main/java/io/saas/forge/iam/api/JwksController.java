package io.saas.forge.iam.api;

import io.saas.forge.iam.contract.api.DiscoveryApi;
import io.saas.forge.iam.contract.model.Jwk;
import io.saas.forge.iam.contract.model.Jwks;
import io.saas.forge.iam.domain.signing.SigningKey;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController implements DiscoveryApi {

    private static final Duration CACHE_DURATION = Duration.ofMinutes(5);

    private final SigningKeyRepository signingKeyRepository;

    public JwksController(SigningKeyRepository signingKeyRepository) {
        this.signingKeyRepository = signingKeyRepository;
    }

    @Override
    public ResponseEntity<Jwks> getJwks() {
        var keys = signingKeyRepository.findPublishedVerificationKeys().stream()
                .map(JwksController::toJwk)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_DURATION))
                .body(new Jwks(keys));
    }

    private static Jwk toJwk(SigningKey key) {
        return new Jwk(
                Jwk.KtyEnum.RSA,
                Jwk.UseEnum.SIG,
                Jwk.AlgEnum.RS256,
                key.kid(),
                key.publicJwkModulus(),
                key.publicJwkExponent());
    }
}

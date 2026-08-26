package io.saasforge.gateway.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class GatewayUserTokenTestConfiguration {

    public static final String VALID_BEARER = "Bearer gateway-test-valid";
    public static final String UNAVAILABLE_BEARER = "Bearer gateway-test-unavailable";

    @Bean
    GatewayUserTokenVerifier gatewayUserTokenVerifier() {
        return authorization -> {
            if (UNAVAILABLE_BEARER.equals(authorization)) {
                throw new GatewayTokenRevocationStatusUnavailableException();
            }
            if (!VALID_BEARER.equals(authorization)) {
                throw new GatewayUserTokenInvalidException();
            }
        };
    }
}

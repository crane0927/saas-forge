package io.saasforge.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RedisGatewayServiceTokenRevocationCheckerTest {

    private static final UUID CLIENT_ID = UUID.fromString("0198f98d-83c2-75d0-82bd-ebdee5d0c613");

    @Test
    void allowsOnlyReadyIndexWithoutClientOrKidRevocation() {
        AtomicReference<List<String>> observedKeys = new AtomicReference<>();
        GatewayRedisValueReader values = keys -> {
            observedKeys.set(keys);
            return Arrays.asList("1", null, null);
        };

        new RedisGatewayServiceTokenRevocationChecker(values, "test")
                .assertAllowed(CLIENT_ID, "kid-1");

        GatewayIamRevocationRedisKeys keys = new GatewayIamRevocationRedisKeys("test");
        assertEquals(List.of(keys.ready(), keys.oauthClient(CLIENT_ID), keys.kid("kid-1")), observedKeys.get());
    }

    @Test
    void rejectsRevokedClientAndKid() {
        for (int index = 1; index < 3; index++) {
            String[] values = {"1", null, null};
            values[index] = "1";
            RedisGatewayServiceTokenRevocationChecker checker = new RedisGatewayServiceTokenRevocationChecker(
                    keys -> Arrays.asList(values), "test");

            assertThrows(GatewayServiceTokenInvalidException.class,
                    () -> checker.assertAllowed(CLIENT_ID, "kid-1"));
        }
    }

    @Test
    void treatsNotReadyMalformedAndRedisFailureAsUnavailable() {
        for (GatewayRedisValueReader unavailable : List.<GatewayRedisValueReader>of(
                keys -> Arrays.asList("0", null, null),
                keys -> List.of("1"),
                keys -> {
                    throw new DataAccessResourceFailureException("Redis unavailable");
                })) {
            assertThrows(GatewayTokenRevocationStatusUnavailableException.class,
                    () -> new RedisGatewayServiceTokenRevocationChecker(unavailable, "test")
                            .assertAllowed(CLIENT_ID, "kid-1"));
        }
    }
}

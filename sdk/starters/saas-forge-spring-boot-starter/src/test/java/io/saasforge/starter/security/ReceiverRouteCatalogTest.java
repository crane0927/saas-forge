package io.saasforge.starter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.contracts.route.HttpRouteCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiverRouteCatalogTest {

    @Test
    void matchesOnlyOperationsOwnedByTheCurrentService() {
        ReceiverRouteCatalog catalog = new ReceiverRouteCatalog(new HttpRouteCatalog(1, List.of(
                route("readCurrent", "GET", "/api/items/{itemId}", "receiver-service"),
                route("readOther", "GET", "/api/other", "other-service"))), "receiver-service");

        assertNotNull(catalog.matching("GET", "/api/items/42"));
        assertNull(catalog.matching("POST", "/api/items/42"));
        assertNull(catalog.matching("GET", "/api/other"));
    }

    @Test
    void rejectsMissingIllegalVersionAndOwnershipMismatchAtStartup() {
        HttpRouteCatalog valid = new HttpRouteCatalog(1, List.of(
                route("readOther", "GET", "/api/other", "other-service")));

        assertThrows(IllegalStateException.class, () -> new ReceiverRouteCatalog(null, "receiver-service"));
        assertThrows(IllegalStateException.class, () -> new ReceiverRouteCatalog(
                new HttpRouteCatalog(2, valid.routes()), "receiver-service"));
        assertThrows(IllegalStateException.class, () -> new ReceiverRouteCatalog(valid, "Receiver"));
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> new ReceiverRouteCatalog(valid, "receiver-service"));
        assertEquals("当前服务与 HTTP Route Catalog 路由归属不匹配: receiver-service", mismatch.getMessage());
    }

    private static HttpRouteCatalog.Route route(String operationId, String method, String path, String serviceId) {
        return new HttpRouteCatalog.Route(
                operationId,
                HttpRouteCatalog.HttpMethod.valueOf(method),
                path,
                serviceId,
                HttpRouteCatalog.CredentialRequirement.USER_REQUIRED,
                List.of());
    }
}

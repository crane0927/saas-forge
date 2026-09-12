package io.saasforge.contracts.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpRouteCatalogLoaderTest {

    @Test
    void loadsThePublishedCatalog() {
        HttpRouteCatalog catalog = HttpRouteCatalogLoader.load();
        long acceptanceRoutes = catalog.routes().stream()
                .filter(route -> route.path().startsWith("/__test/"))
                .count();

        assertEquals(HttpRouteCatalogLoader.SUPPORTED_SCHEMA_VERSION, catalog.schemaVersion());
        assertEquals(42 + acceptanceRoutes, catalog.routes().size());
        for (String operation : java.util.List.of("listQuotaDefinitions", "getQuotaDefinition",
                "listQuotaDefinitionOperations", "getQuotaDefinitionOperation", "recoverQuotaDefinitionOperation", "listPlans", "getPlan", "listPlanOperations", "getPlanOperation", "recoverPlanOperation")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation)).findFirst().orElseThrow();
            assertEquals("entitlement-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
    }

    @Test
    void rejectsUnsupportedMissingUnknownAndEmptyCatalogData() {
        assertInvalid("{\"schemaVersion\":2,\"routes\":[]}");
        assertInvalid("{\"routes\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[],\"unknown\":true}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[{\"operationId\":\"bad\",\"method\":\"GET\","
                + "\"path\":\"not-absolute\",\"serviceId\":\"iam-service\","
                + "\"credentialRequirement\":\"ANONYMOUS\",\"requiredScopes\":[]}]}");
    }

    private void assertInvalid(String json) {
        var input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> HttpRouteCatalogLoader.load(input));
    }
}

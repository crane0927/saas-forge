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

        assertEquals(HttpRouteCatalogLoader.SUPPORTED_SCHEMA_VERSION, catalog.schemaVersion());
        assertEquals(25, catalog.routes().size());
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

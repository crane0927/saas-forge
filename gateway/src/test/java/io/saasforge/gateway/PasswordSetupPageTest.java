package io.saasforge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.saasforge.gateway.config.GatewayUserTokenTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "saasforge.gateway.configuration-revision=test"
})
@ActiveProfiles("gateway-test")
@Import(GatewayUserTokenTestConfiguration.class)
class PasswordSetupPageTest {
    @LocalServerPort private int port;

    @Test
    void servesOnlyLocalResourcesWithSensitivePageHeaders() throws Exception {
        HttpResponse<String> page = get("/password-setup");

        assertEquals(200, page.statusCode());
        assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow());
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("default-src 'none'"));
        assertTrue(csp.contains("script-src 'self'"));
        assertFalse(page.body().contains("http://"));
        assertFalse(page.body().contains("https://"));
        assertTrue(page.body().contains("/password-setup/app.js"));
        assertTrue(page.body().contains("/password-setup/styles.css"));
    }

    @Test
    void scriptClearsFragmentAndSubmitsOnlyTokenAndPasswordInJsonBody() throws Exception {
        HttpResponse<String> script = get("/password-setup/app.js");

        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("window.history.replaceState(null, '', window.location.pathname)"));
        assertTrue(script.body().contains("body: JSON.stringify({ token: setupToken, newPassword: newPassword.value })"));
        assertTrue(script.body().contains("referrerPolicy: 'no-referrer'"));
        assertFalse(script.body().contains("localStorage"));
        assertFalse(script.body().contains("sessionStorage"));
        assertFalse(script.body().contains("console."));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

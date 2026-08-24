package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.authentication.BrowserRequestRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserRequestSecurityTest {
    private final BrowserRequestSecurity security = new BrowserRequestSecurity("saasforge.test");

    @Test
    void acceptsControlledOriginsAndMissingFetchMetadata() {
        MockHttpServletRequest consoleRequest = request("https://console.saasforge.test", null);
        assertDoesNotThrow(() -> security.requireControlledMutation(consoleRequest, "1"));
        MockHttpServletRequest platformRequest = request("https://platform.saasforge.test", "same-site");
        assertDoesNotThrow(() -> security.requireControlledMutation(platformRequest, "1"));
    }

    @Test
    void rejectsExternalOriginCrossSiteFetchWrongCsrfAndNonJson() {
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(request("https://evil.test", "same-site"), "1"));
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://console.saasforge.test", "cross-site"), "1"));
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://console.saasforge.test", "same-site"), "csrf"));
        MockHttpServletRequest text = request("https://console.saasforge.test", "same-site");
        text.setContentType("text/plain");
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(text, "1"));
    }

    private static MockHttpServletRequest request(String origin, String fetchSite) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/tenant-switches");
        request.addHeader("Origin", origin);
        if (fetchSite != null) {
            request.addHeader("Sec-Fetch-Site", fetchSite);
        }
        request.setContentType("application/json");
        return request;
    }
}

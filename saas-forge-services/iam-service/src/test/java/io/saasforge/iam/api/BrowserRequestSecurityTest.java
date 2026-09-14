package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.authentication.BrowserRequestRejectedException;
import io.saasforge.iam.application.authentication.BrowserSessionSlot;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserRequestSecurityTest {
    private final BrowserRequestSecurity security = new BrowserRequestSecurity("saasforge.test");

    @Test
    void acceptsControlledOriginsAndMissingFetchMetadata() {
        MockHttpServletRequest consoleRequest = request("https://console.saasforge.test", null);
        assertDoesNotThrow(() -> security.requireControlledMutation(
                consoleRequest, "1", BrowserSessionSlot.TENANT));
        MockHttpServletRequest platformRequest = request("https://platform.saasforge.test", "same-site");
        assertDoesNotThrow(() -> security.requireControlledMutation(
                platformRequest, "1", BrowserSessionSlot.PLATFORM));
    }

    @Test
    void rejectsExternalOriginCrossSiteFetchWrongCsrfAndNonJson() {
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://evil.test", "same-site"), "1", BrowserSessionSlot.TENANT));
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://console.saasforge.test", "cross-site"), "1", BrowserSessionSlot.TENANT));
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://console.saasforge.test", "same-site"), "csrf", BrowserSessionSlot.TENANT));
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(
                        request("https://console.saasforge.test", "same-site"), "1", BrowserSessionSlot.PLATFORM));
        MockHttpServletRequest text = request("https://console.saasforge.test", "same-site");
        text.setContentType("text/plain");
        assertThrows(BrowserRequestRejectedException.class,
                () -> security.requireControlledMutation(text, "1", BrowserSessionSlot.TENANT));
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

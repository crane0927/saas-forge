package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.authentication.ContextSelectionSessionInvalidException;
import io.saasforge.iam.application.authentication.PasswordChangeSessionInvalidException;
import io.saasforge.iam.application.authentication.RefreshSessionInvalidException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingRequestCookieException;

class AuthenticationExceptionHandlerTest {
    private final AuthenticationExceptionHandler handler = new AuthenticationExceptionHandler();

    @Test
    void mapsMissingRefreshCookieByAuthenticationFlow() throws Exception {
        assertMissingCookieCode("/api/v1/auth/password-changes", PasswordChangeSessionInvalidException.CODE);
        assertMissingCookieCode("/api/v1/auth/refresh", RefreshSessionInvalidException.CODE);
        assertMissingCookieCode("/api/v1/auth/contexts", ContextSelectionSessionInvalidException.CODE);
    }

    @Test
    void rethrowsMissingCookiesItDoesNotOwn() {
        MissingRequestCookieException exception = new MissingRequestCookieException("other", null);
        assertThrows(MissingRequestCookieException.class,
                () -> handler.missingRefreshCookie(exception, request("/api/v1/auth/refresh")));
    }

    private void assertMissingCookieCode(String uri, String code) throws Exception {
        MissingRequestCookieException exception = new MissingRequestCookieException("__Host-sf_refresh", null);
        var response = handler.missingRefreshCookie(exception, request(uri));
        assertEquals(code, response.getBody().code());
        assertEquals(32, response.getBody().traceId().length());
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}

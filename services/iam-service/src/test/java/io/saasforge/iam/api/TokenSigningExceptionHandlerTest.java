package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.saasforge.iam.application.signing.TokenSigningUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

class TokenSigningExceptionHandlerTest {

    @Test
    void mapsSigningFailureToTheStable503ProblemContract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");

        var response = new TokenSigningExceptionHandler().handle(
                new TokenSigningUnavailableException(new IllegalStateException("KMS unavailable")), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("TOKEN_SIGNING_UNAVAILABLE", response.getBody().code());
        assertEquals("urn:saasforge:problem:token-signing-unavailable", response.getBody().type().toString());
        assertEquals("0123456789abcdef0123456789abcdef", response.getBody().traceId());
    }
}

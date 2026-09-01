package io.saasforge.starter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.UserAccessTokenInvalidException;
import io.saasforge.sdk.auth.VerifiedServiceAccessTokenClaims;
import io.saasforge.sdk.auth.VerifiedUserAccessTokenClaims;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class HttpReceiverAuthenticationFilterTest {

    private static final UUID IDENTITY_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdea");
    private static final UUID TENANT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdeb");
    private static final UUID CLIENT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdec");
    private static final UUID JTI = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcded");

    private UserTokenClaimsVerifier userSignatures;
    private ServiceTokenClaimsVerifier serviceSignatures;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
        userSignatures = authorization -> {
            throw new UserAccessTokenInvalidException();
        };
        serviceSignatures = token -> {
            throw new ServiceAccessTokenInvalidException();
        };
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesMutuallyExclusivePrincipalsBeforeControllerAndClearsAfterRequest() throws Exception {
        userSignatures = authorization -> userClaims(MEMBERSHIP_ID, TENANT_ID);
        serviceSignatures = token -> serviceClaims(Set.of(
                "runtime:read", "runtime:quota:write", "runtime:extra"));
        HttpReceiverAuthenticationFilter filter = filter((jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> false);

        MockHttpServletRequest userRequest = request("GET", "/api/user", "Bearer user-token");
        MockHttpServletResponse userResponse = new MockHttpServletResponse();
        filter.doFilter(userRequest, userResponse, (request, response) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            UserAuthenticationContext principal = assertInstanceOf(
                    UserAuthenticationContext.class, authentication.getPrincipal());
            assertEquals(IDENTITY_ID, principal.identityId());
            assertEquals(MEMBERSHIP_ID, principal.membershipId());
            assertEquals(TENANT_ID, principal.tenantId());
            assertChildThreadHasNoAuthentication();
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        MockHttpServletRequest serviceRequest = request("POST", "/api/service/tenant-123", "Bearer service-token");
        MockHttpServletResponse serviceResponse = new MockHttpServletResponse();
        filter.doFilter(serviceRequest, serviceResponse, (request, response) -> {
            ServiceAuthenticationContext principal = assertInstanceOf(
                    ServiceAuthenticationContext.class,
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertEquals(CLIENT_ID, principal.clientId());
            assertEquals(Set.of("runtime:read", "runtime:quota:write", "runtime:extra"), principal.scopes());
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsMissingWrongTypeInvalidAndDuplicateCredentialsBeforeController() throws Exception {
        HttpReceiverAuthenticationFilter filter = filter((jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> false);

        assertUnauthorized(filter, request("GET", "/api/user", null), "Bearer");
        assertUnauthorized(filter, request("GET", "/api/user", "Bearer service-token"),
                "Bearer error=\"invalid_token\"");
        assertUnauthorized(filter, request("POST", "/api/service/target", "Bearer user-token"),
                "Bearer error=\"invalid_token\"");
        MockHttpServletRequest duplicate = request("POST", "/api/service/target", "Bearer one");
        duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer two");
        assertUnauthorized(filter, duplicate, "Bearer error=\"invalid_token\"");
    }

    @Test
    void mapsScopeAndRevocationFailuresToGatewayProblemSemantics() throws Exception {
        serviceSignatures = token -> serviceClaims(Set.of("runtime:read"));
        HttpReceiverAuthenticationFilter narrow = filter((jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> false);
        MockHttpServletResponse forbidden = invoke(
                narrow, request("POST", "/api/service/target", "Bearer narrow-token"));
        assertEquals(403, forbidden.getStatus());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"runtime:quota:write runtime:read\"",
                forbidden.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertTrue(forbidden.getContentAsString().contains("\"code\":\"ACCESS_TOKEN_SCOPE_INSUFFICIENT\""));

        userSignatures = authorization -> userClaims(null, null);
        HttpReceiverAuthenticationFilter unavailable = filter((jti, kid, membershipId, tenantId) -> {
            throw new IllegalStateException("Redis is not ready");
        }, (clientId, kid) -> false);
        MockHttpServletResponse serviceUnavailable = invoke(
                unavailable, request("GET", "/api/user", "Bearer user-token"));
        assertEquals(503, serviceUnavailable.getStatus());
        assertTrue(serviceUnavailable.getContentAsString()
                .contains("\"code\":\"TOKEN_REVOCATION_STATUS_UNAVAILABLE\""));
        assertTrue(serviceUnavailable.getContentAsString()
                .contains("The User Token revocation status is unavailable."));
    }

    @Test
    void revokedTokensAreUnauthorizedAndServiceRevocationOutageIsUnavailable() throws Exception {
        userSignatures = authorization -> userClaims(null, null);
        serviceSignatures = token -> serviceClaims(Set.of("runtime:quota:write", "runtime:read"));

        HttpReceiverAuthenticationFilter revoked = filter(
                (jti, kid, membershipId, tenantId) -> true,
                (clientId, kid) -> true);
        assertUnauthorized(revoked, request("GET", "/api/user", "Bearer user-token"),
                "Bearer error=\"invalid_token\"");
        assertUnauthorized(revoked, request("POST", "/api/service/target", "Bearer service-token"),
                "Bearer error=\"invalid_token\"");

        HttpReceiverAuthenticationFilter unavailable = filter(
                (jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> {
                    throw new IllegalStateException("Redis is not ready");
                });
        MockHttpServletResponse response = invoke(
                unavailable, request("POST", "/api/service/target", "Bearer service-token"));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("The Service Token revocation status is unavailable."));
    }

    @Test
    void optionalInvalidUserTokenDoesNotCreateContextOrBlockCookieCleanup() throws Exception {
        HttpReceiverAuthenticationFilter filter = filter((jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> false);
        AtomicReference<Authentication> observed = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("DELETE", "/api/logout", "Bearer invalid"), response,
                (request, servletResponse) -> observed.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals(200, response.getStatus());
        assertNull(observed.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        MockHttpServletRequest duplicate = request("DELETE", "/api/logout", "Bearer invalid");
        duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer another");
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicateResponse, (request, servletResponse) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
        assertEquals(200, duplicateResponse.getStatus());
    }

    @Test
    void rejectsReservedContextHeadersBeforeEveryOperationKind() throws Exception {
        HttpReceiverAuthenticationFilter filter = filter((jti, kid, membershipId, tenantId) -> false,
                (clientId, kid) -> false);
        List<RequestTarget> targets = List.of(
                new RequestTarget("GET", "/api/user", "Bearer user-token"),
                new RequestTarget("POST", "/api/service/target", "Bearer service-token"),
                new RequestTarget("GET", "/api/anonymous", null),
                new RequestTarget("POST", "/api/cookie", null));

        for (String header : List.of(
                "X-Identity",
                "x-membership",
                "X-TENANT-context",
                "x-ROLE",
                "X-Permission",
                "x-scope",
                "X-cLiEnT")) {
            for (RequestTarget target : targets) {
                MockHttpServletRequest request = request(target.method(), target.path(), target.authorization());
                request.addHeader(header, "forged");

                MockHttpServletResponse response = invoke(filter, request);

                assertEquals(400, response.getStatus(), header + " on " + target.path());
                assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
                assertTrue(response.getContentAsString().contains("\"code\":\"UNTRUSTED_CONTEXT_HEADER\""));
            }
        }
    }

    private HttpReceiverAuthenticationFilter filter(
            UserAccessTokenContextRevocationChecker userRevocations,
            ServiceAccessTokenRevocationChecker serviceRevocations) {
        ReceiverTokenAuthenticators authenticators = new ReceiverTokenAuthenticators(
                userSignatures, userRevocations, serviceSignatures, serviceRevocations);
        return new HttpReceiverAuthenticationFilter(
                new ReceiverRouteCatalog(catalog(), "receiver-service"),
                authenticators,
                new ReceiverProblemDetailsWriter(new ObjectMapper()));
    }

    private static HttpRouteCatalog catalog() {
        return new HttpRouteCatalog(1, List.of(
                route("logout", HttpRouteCatalog.HttpMethod.DELETE, "/api/logout",
                        HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL, List.of()),
                route("readUser", HttpRouteCatalog.HttpMethod.GET, "/api/user",
                        HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()),
                route("writeService", HttpRouteCatalog.HttpMethod.POST, "/api/service/{tenantId}",
                        HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED,
                        List.of("runtime:quota:write", "runtime:read")),
                route("anonymous", HttpRouteCatalog.HttpMethod.GET, "/api/anonymous",
                        HttpRouteCatalog.CredentialRequirement.ANONYMOUS, List.of()),
                route("cookie", HttpRouteCatalog.HttpMethod.POST, "/api/cookie",
                        HttpRouteCatalog.CredentialRequirement.BROWSER_SESSION_SLOT_REQUIRED, List.of())));
    }

    private static HttpRouteCatalog.Route route(
            String operationId,
            HttpRouteCatalog.HttpMethod method,
            String path,
            HttpRouteCatalog.CredentialRequirement requirement,
            List<String> scopes) {
        return new HttpRouteCatalog.Route(operationId, method, path, "receiver-service", requirement, scopes);
    }

    private static MockHttpServletRequest request(String method, String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        return request;
    }

    private static MockHttpServletResponse invoke(
            HttpReceiverAuthenticationFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("认证失败后不得到达 Controller");
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        return response;
    }

    private static void assertUnauthorized(
            HttpReceiverAuthenticationFilter filter, MockHttpServletRequest request, String challenge) throws Exception {
        MockHttpServletResponse response = invoke(filter, request);
        assertEquals(401, response.getStatus());
        assertEquals(challenge, response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertTrue(response.getContentAsString().contains("\"code\":\"ACCESS_TOKEN_INVALID\""));
    }

    private static VerifiedUserAccessTokenClaims userClaims(UUID membershipId, UUID tenantId) {
        return new VerifiedUserAccessTokenClaims(
                IDENTITY_ID, JTI, "kid", Instant.EPOCH, Instant.MAX, membershipId, tenantId);
    }

    private static VerifiedServiceAccessTokenClaims serviceClaims(Set<String> scopes) {
        return new VerifiedServiceAccessTokenClaims(
                CLIENT_ID, scopes, JTI, "kid", Instant.EPOCH, Instant.MAX);
    }

    private static void assertChildThreadHasNoAuthentication() {
        AtomicReference<Authentication> childAuthentication = new AtomicReference<>();
        Thread child = new Thread(() -> childAuthentication.set(
                SecurityContextHolder.getContext().getAuthentication()));
        child.start();
        try {
            child.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待子线程验证被中断", exception);
        }
        assertNull(childAuthentication.get());
    }

    private record RequestTarget(String method, String path, String authorization) {
    }
}

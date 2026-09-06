package io.saasforge.starter.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.sdk.auth.IdentityContextAccessor;
import io.saasforge.sdk.auth.VerifiedServiceAccessTokenClaims;
import io.saasforge.sdk.auth.VerifiedUserAccessTokenClaims;
import io.saasforge.sdk.tenant.TenantContextAccessor;
import io.saasforge.sdk.tenant.TenantContextUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class TenantContextHttpTest {

    private static final UUID IDENTITY_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdea");
    private static final UUID TENANT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdeb");
    private static final UUID CLIENT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdec");
    private static final UUID JTI = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcded");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void tenantUserRequestExposesConsistentIdentityAndTenantContexts() throws Exception {
        IdentityContextAccessor identities = new SpringSecurityIdentityContextAccessor();
        TenantContextAccessor tenants = new SpringSecurityTenantContextAccessor();
        MockMvc http = http(identities, tenants, MEMBERSHIP_ID, TENANT_ID);

        http.perform(get("/api/tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(IDENTITY_ID + ":" + MEMBERSHIP_ID + ":" + TENANT_ID));
        assertThrows(TenantContextUnavailableException.class, tenants::requireCurrent);
    }

    @Test
    void platformUserRequestCannotObtainTenantContext() throws Exception {
        MockMvc http = http(
                new SpringSecurityIdentityContextAccessor(),
                new SpringSecurityTenantContextAccessor(),
                null,
                null);

        http.perform(get("/api/tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer platform-user-token")
                        .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(content().string(not(containsString("platform-user-token"))));
    }

    @Test
    void serviceAndAnonymousRequestsCannotObtainTenantContext() throws Exception {
        MockMvc http = http(
                new SpringSecurityIdentityContextAccessor(),
                new SpringSecurityTenantContextAccessor(),
                MEMBERSHIP_ID,
                TENANT_ID);

        http.perform(get("/api/service-tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                        .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
        http.perform(get("/api/anonymous-tenant-context")
                        .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void rejectsForgedReservedContextHeaderBeforeBusinessCode() throws Exception {
        MockMvc http = http(
                new SpringSecurityIdentityContextAccessor(),
                new SpringSecurityTenantContextAccessor(),
                MEMBERSHIP_ID,
                TENANT_ID);

        http.perform(get("/api/tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-user-token")
                        .header("X-Tenant-Context", "forged-tenant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNTRUSTED_CONTEXT_HEADER"))
                .andExpect(content().string(not(containsString("forged-tenant"))));
    }

    @Test
    void doesNotPropagateTenantContextToChildThreads() throws Exception {
        MockMvc http = http(
                new SpringSecurityIdentityContextAccessor(),
                new SpringSecurityTenantContextAccessor(),
                MEMBERSHIP_ID,
                TENANT_ID);

        http.perform(get("/api/child-thread-tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tenant-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("unavailable"));
    }

    private static MockMvc http(
            IdentityContextAccessor identities,
            TenantContextAccessor tenants,
            UUID verifiedMembershipId,
            UUID verifiedTenantId) {
        UserTokenClaimsVerifier userSignatures = authorization -> new VerifiedUserAccessTokenClaims(
                IDENTITY_ID, JTI, "kid", Instant.EPOCH, Instant.MAX, verifiedMembershipId, verifiedTenantId);
        ServiceTokenClaimsVerifier serviceSignatures = token -> new VerifiedServiceAccessTokenClaims(
                CLIENT_ID, Set.of("runtime:read"), JTI, "kid", Instant.EPOCH, Instant.MAX);
        ReceiverTokenAuthenticators authenticators = new ReceiverTokenAuthenticators(
                userSignatures,
                (jti, kid, membershipId, tenantId) -> false,
                serviceSignatures,
                (clientId, kid) -> false);
        HttpReceiverAuthenticationFilter filter = new HttpReceiverAuthenticationFilter(
                new ReceiverRouteCatalog(catalog(), "receiver-service"),
                authenticators,
                new ReceiverProblemDetailsWriter(new ObjectMapper()));
        return MockMvcBuilders.standaloneSetup(new TenantContextController(identities, tenants))
                .addFilters(filter)
                .build();
    }

    private static HttpRouteCatalog catalog() {
        return new HttpRouteCatalog(1, List.of(
                route("readTenantContext", "/api/tenant-context",
                        HttpRouteCatalog.CredentialRequirement.USER_REQUIRED),
                route("readServiceTenantContext", "/api/service-tenant-context",
                        HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED),
                route("readAnonymousTenantContext", "/api/anonymous-tenant-context",
                        HttpRouteCatalog.CredentialRequirement.ANONYMOUS),
                route("readChildThreadTenantContext", "/api/child-thread-tenant-context",
                        HttpRouteCatalog.CredentialRequirement.USER_REQUIRED)));
    }

    private static HttpRouteCatalog.Route route(
            String operationId, String path, HttpRouteCatalog.CredentialRequirement credentialRequirement) {
        return new HttpRouteCatalog.Route(
                operationId,
                HttpRouteCatalog.HttpMethod.GET,
                path,
                "receiver-service",
                credentialRequirement,
                List.of());
    }

    @RestController
    private static final class TenantContextController {

        private final IdentityContextAccessor identities;
        private final TenantContextAccessor tenants;

        private TenantContextController(IdentityContextAccessor identities, TenantContextAccessor tenants) {
            this.identities = identities;
            this.tenants = tenants;
        }

        @GetMapping("/api/tenant-context")
        String current() {
            UUID identityId = identities.current().orElseThrow().identityId();
            var tenant = tenants.requireCurrent();
            return identityId + ":" + tenant.membershipId() + ":" + tenant.tenantId();
        }

        @GetMapping({"/api/service-tenant-context", "/api/anonymous-tenant-context"})
        String required() {
            return tenants.requireCurrent().tenantId().toString();
        }

        @GetMapping("/api/child-thread-tenant-context")
        String childThread() throws InterruptedException {
            AtomicReference<String> observed = new AtomicReference<>();
            Thread child = new Thread(() -> {
                try {
                    observed.set(tenants.requireCurrent().tenantId().toString());
                } catch (TenantContextUnavailableException exception) {
                    observed.set("unavailable");
                }
            });
            child.start();
            child.join();
            return observed.get();
        }
    }
}

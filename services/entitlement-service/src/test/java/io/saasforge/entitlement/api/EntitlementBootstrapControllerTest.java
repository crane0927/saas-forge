package io.saasforge.entitlement.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.entitlement.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EntitlementBootstrapControllerTest {
    private static final UUID KEY = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final UUID DEFINITION = UUID.fromString("019535d9-0000-7000-8000-000000000002");

    @Test
    void rejectsWriteWhenIamDoesNotConfirmCurrentPlatformAdminRole() throws Exception {
        PlatformAdminAuthorizer authorizer = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        MockMvc mvc = mvc(authorizer, unusedBootstrap());

        mvc.perform(post("/api/v1/platform/quota-definitions")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"max_users\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void createsDraftQuotaDefinitionWithContractLocationAndBody() throws Exception {
        EntitlementBootstrapService bootstrap = new EntitlementBootstrapService(
                null, null, null, null, null, null, null) {
            @Override
            public QuotaDefinitionResult createQuotaDefinition(
                    UUID actor, UUID key, String code, String traceId) {
                return new QuotaDefinitionResult(
                        DEFINITION, code, QuotaDefinitionStatus.DRAFT,
                        Instant.parse("2026-08-23T02:00:00Z"), Instant.parse("2026-08-23T02:00:00Z"));
            }
        };
        MockMvc mvc = mvc(authorization -> KEY, bootstrap);

        mvc.perform(post("/api/v1/platform/quota-definitions")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"max_users\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/platform/quota-definitions/" + DEFINITION))
                .andExpect(jsonPath("$.code").value("max_users"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer, EntitlementBootstrapService bootstrap) {
        return MockMvcBuilders.standaloneSetup(new EntitlementBootstrapController(authorizer, bootstrap))
                .setControllerAdvice(new EntitlementBootstrapExceptionHandler())
                .build();
    }

    private static EntitlementBootstrapService unusedBootstrap() {
        return new EntitlementBootstrapService(null, null, null, null, null, null, null) {
            @Override
            public QuotaDefinitionResult createQuotaDefinition(
                    UUID actor, UUID key, String code, String traceId) {
                throw new AssertionError("不应进入 Entitlement 管理用例");
            }
        };
    }
}

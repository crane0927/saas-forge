package io.saasforge.tenantaccess.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.tenantaccess.application.tenant.CreatePendingTenantService;
import io.saasforge.tenantaccess.application.tenant.TenantCreationResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantCreationControllerTest {
    private static final UUID KEY = UUID.fromString("019535d9-0000-7000-8000-000000000001");

    @Test
    void rejectsRequestWhenIamDoesNotConfirmPlatformAdminRole() throws Exception {
        PlatformAdminAuthorizer authorizer = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        CreatePendingTenantService creation = unusedCreation();
        MockMvc mvc = mvc(authorizer, creation);

        mvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Acme\",\"expiresAt\":\"2026-08-24T01:00:00.000Z\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void requiresAbsoluteExpiryBeforeInvokingTheUseCase() throws Exception {
        PlatformAdminAuthorizer authorizer = authorization -> KEY;
        CreatePendingTenantService creation = unusedCreation();
        MockMvc mvc = mvc(authorizer, creation);

        mvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer, CreatePendingTenantService creation) {
        return MockMvcBuilders.standaloneSetup(new TenantCreationController(authorizer, creation))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();
    }

    private static CreatePendingTenantService unusedCreation() {
        return new CreatePendingTenantService(null, null, null, null, null, null) {
            @Override
            public TenantCreationResult create(
                    UUID callerIdentityId,
                    UUID idempotencyKey,
                    String displayName,
                    Instant expiresAt,
                    String traceId) {
                throw new AssertionError("不应进入 Tenant 创建用例");
            }
        };
    }
}

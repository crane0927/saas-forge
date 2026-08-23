package io.saasforge.tenantaccess.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
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

    @Test
    void initializesAdministratorOnlyAfterPlatformAuthorization() throws Exception {
        UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000010");
        Instant expiry = Instant.parse("2026-08-24T01:00:00Z");
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                null, null, null, null, null, null) {
            @Override
            public TenantAdministratorInitializationResult initialize(
                    UUID actorIdentityId,
                    UUID idempotencyKey,
                    UUID requestedTenantId,
                    String email,
                    String displayName,
                    String traceId) {
                return new TenantAdministratorInitializationResult(
                        requestedTenantId, "Acme", TenantStatus.ACTIVE, expiry,
                        Instant.parse("2026-08-23T01:00:00Z"), Instant.parse("2026-08-23T02:00:00Z"));
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new TenantCreationController(authorization -> KEY, unusedCreation(), initialization))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations", tenantId)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\",\"administratorDisplayName\":\"Admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void rejectsAdministratorInitializationWhenCurrentPlatformRoleIsMissing() throws Exception {
        PlatformAdminAuthorizer denied = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        MockMvc mvc = mvc(denied, unusedCreation());

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void compensationInProgressReturnsServiceUnavailableWithRetryAfter() throws Exception {
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                null, null, null, null, null, null) {
            @Override
            public TenantAdministratorInitializationResult initialize(
                    UUID actorIdentityId,
                    UUID idempotencyKey,
                    UUID tenantId,
                    String email,
                    String displayName,
                    String traceId) {
                throw new TenantAdministratorInitializationException(
                        "TENANT_ADMIN_INITIALIZATION_COMPENSATING", "正在补偿", 7);
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorization -> KEY, unusedCreation(), initialization))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("TENANT_ADMIN_INITIALIZATION_COMPENSATING"));
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer, CreatePendingTenantService creation) {
        return MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorizer, creation,
                        new InitializeTenantAdministratorService(null, null, null, null, null, null)))
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

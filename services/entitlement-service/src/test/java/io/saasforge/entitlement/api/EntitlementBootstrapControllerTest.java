package io.saasforge.entitlement.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.entitlement.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
import io.saasforge.entitlement.domain.subscription.SubscriptionStatus;
import io.saasforge.entitlement.domain.plan.PlanQuotaLimit;
import io.saasforge.entitlement.domain.plan.PlanStatus;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

    @Test
    void createsInitialActiveSubscriptionWithContractLocationAndBody() throws Exception {
        UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000010");
        UUID planId = UUID.fromString("019535d9-0000-7000-8000-000000000011");
        UUID subscriptionId = UUID.fromString("019535d9-0000-7000-8000-000000000012");
        CreateInitialSubscriptionService subscriptions = new CreateInitialSubscriptionService(
                null, null, null, null, null, null, null, null) {
            @Override
            public InitialSubscriptionResult create(
                    UUID actor, UUID key, UUID tenant, UUID plan, Instant endsAt, String traceId) {
                return new InitialSubscriptionResult(
                        subscriptionId, tenant, plan, SubscriptionStatus.ACTIVE, endsAt,
                        Instant.parse("2026-08-23T06:00:00Z"));
            }
        };
        MockMvc mvc = mvc(authorization -> KEY, unusedBootstrap(), subscriptions);

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/subscriptions", tenantId)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + planId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/platform/tenants/" + tenantId
                        + "/subscriptions/" + subscriptionId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));
    }

    @Test
    void createsAndActivatesPlanAndQuotaDefinition() throws Exception {
        UUID planId = UUID.fromString("019535d9-0000-7000-8000-000000000020");
        Instant now = Instant.parse("2026-08-24T02:00:00Z");
        EntitlementBootstrapService bootstrap = Mockito.mock(EntitlementBootstrapService.class);
        Mockito.when(bootstrap.createPlan(Mockito.any(), Mockito.eq(KEY), Mockito.eq("starter"),
                        Mockito.eq("Starter"), Mockito.eq(DEFINITION), Mockito.eq(10), Mockito.any()))
                .thenReturn(new PlanResult(planId, "starter", "Starter", PlanStatus.DRAFT,
                        List.of(new PlanQuotaLimit(DEFINITION, 10)), now, now));
        Mockito.when(bootstrap.activatePlan(Mockito.any(), Mockito.eq(KEY), Mockito.eq(planId), Mockito.any()))
                .thenReturn(new PlanResult(planId, "starter", "Starter", PlanStatus.ACTIVE,
                        List.of(new PlanQuotaLimit(DEFINITION, 10)), now, now));
        Mockito.when(bootstrap.activateQuotaDefinition(
                        Mockito.any(), Mockito.eq(KEY), Mockito.eq(DEFINITION), Mockito.any()))
                .thenReturn(new QuotaDefinitionResult(
                        DEFINITION, "max_users", QuotaDefinitionStatus.ACTIVE, now, now));
        MockMvc mvc = mvc(authorization -> KEY, bootstrap);

        mvc.perform(post("/api/v1/platform/plans")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"starter\",\"displayName\":\"Starter\",\"quotaLimits\":[{"
                                + "\"quotaDefinitionId\":\"" + DEFINITION + "\",\"limit\":10}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/platform/plans/" + planId))
                .andExpect(jsonPath("$.quotaLimits[0].limit").value(10));
        mvc.perform(post("/api/v1/platform/plans/{planId}/activations", planId)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/platform/quota-definitions/{id}/activations", DEFINITION)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void rejectsPlanWithoutExactlyOneQuotaLimit() throws Exception {
        MockMvc mvc = mvc(authorization -> KEY, unusedBootstrap());

        mvc.perform(post("/api/v1/platform/plans")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"starter\",\"displayName\":\"Starter\",\"quotaLimits\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer, EntitlementBootstrapService bootstrap) {
        return mvc(authorizer, bootstrap, null);
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer,
            EntitlementBootstrapService bootstrap,
            CreateInitialSubscriptionService subscriptions) {
        return MockMvcBuilders.standaloneSetup(
                        new EntitlementBootstrapController(authorizer, bootstrap, subscriptions))
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

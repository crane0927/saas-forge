package io.saasforge.acceptance.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.auth.IdentityContext;
import io.saasforge.sdk.tenant.TenantContextSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = {ExternalConsumerApplication.class, ExternalConsumerTestAuthenticationConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.application.name=sdk-external-consumer-fixture",
            "server.tomcat.threads.max=1",
            "server.tomcat.threads.min-spare=1"
        })
class ExternalConsumerHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT_PATH = "/__test/sdk-consumer/tenant-context";
    private static final String PLATFORM_PATH = "/__test/sdk-consumer/platform-context";
    private static final String SERVICE_PATH = "/__test/sdk-consumer/service-context";
    private static final String ANONYMOUS_PATH = "/__test/sdk-consumer/anonymous-context";
    private static final String STATE_PATH = "/__test/sdk-consumer/context-state";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private TestTokenAuthority tokens;

    @org.springframework.beans.factory.annotation.Autowired
    private TestRevocationState revocations;

    @Test
    void tenantUserReceivesExactImmutableContextsAndRequestCompletionClearsThem() throws Exception {
        HttpResponse<String> response = get(TENANT_PATH, bearer(tokens.tenantUserToken()));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("identityId").asText()).isEqualTo(TestTokenAuthority.IDENTITY_ID.toString());
        assertThat(body.path("membershipId").asText()).isEqualTo(TestTokenAuthority.MEMBERSHIP_ID.toString());
        assertThat(body.path("tenantId").asText()).isEqualTo(TestTokenAuthority.TENANT_ID.toString());
        assertThat(body.path("identityImmutable").asBoolean()).isTrue();
        assertThat(body.path("tenantImmutable").asBoolean()).isTrue();
        assertThat(IdentityContext.class.isRecord()).isTrue();
        assertThat(TenantContextSnapshot.class.isRecord()).isTrue();

        HttpResponse<String> state = get(STATE_PATH, Map.of());
        assertThat(state.statusCode()).isEqualTo(200);
        JsonNode stateBody = JSON.readTree(state.body());
        assertThat(stateBody.path("identityAbsent").asBoolean()).isTrue();
        assertThat(stateBody.path("tenantAbsent").asBoolean()).isTrue();
    }

    @Test
    void platformServiceAndAnonymousRequestsCannotReadTenantContext() throws Exception {
        assertContextUnavailable(get(PLATFORM_PATH, bearer(tokens.platformUserToken())));
        assertContextUnavailable(get(SERVICE_PATH, bearer(tokens.serviceToken())));
        assertContextUnavailable(get(ANONYMOUS_PATH, Map.of()));
    }

    @Test
    void rejectsForgedContextHeadersAndTokenTypeMixing() throws Exception {
        HttpResponse<String> forged = get(TENANT_PATH, Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer " + tokens.tenantUserToken(),
                "X-Tenant-Context", "forged-tenant"));
        assertProblem(forged, 400, "UNTRUSTED_CONTEXT_HEADER");
        assertThat(forged.body()).doesNotContain("forged-tenant");

        assertProblem(get(TENANT_PATH, bearer(tokens.serviceToken())), 401, "ACCESS_TOKEN_INVALID");
        assertProblem(get(SERVICE_PATH, bearer(tokens.tenantUserToken())), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void failsClosedWhenRevocationStatusIsUnavailable() throws Exception {
        revocations.setUnavailable(true);
        try {
            assertProblem(
                    get(TENANT_PATH, bearer(tokens.tenantUserToken())),
                    503,
                    "TOKEN_REVOCATION_STATUS_UNAVAILABLE");
        } finally {
            revocations.setUnavailable(false);
        }
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> bearer(String token) {
        return Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static void assertContextUnavailable(HttpResponse<String> response) throws Exception {
        assertProblem(response, 403, "ACCESS_CONTEXT_UNAVAILABLE");
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}

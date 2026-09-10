package io.saasforge.iam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.iam.application.authentication.MembershipValidation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.RestClient;

class LocalIamDiscoveryTest {
    private final DiscoveryClient discovery = mock(DiscoveryClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MembershipValidationConfiguration.class)
            .withBean(DiscoveryClient.class, () -> discovery)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MembershipValidation.class, () -> mock(MembershipValidation.class))
            .withPropertyValues(
                    "spring.profiles.active=local",
                    "saasforge.iam.http-base-url=http://127.0.0.1:1",
                    "saasforge.iam.service-client-id-file=unused",
                    "saasforge.iam.service-client-secret-file=unused");

    @Test
    void followsIamPortChangesWithoutChangingClientConfiguration() throws Exception {
        HttpServer first = server("first");
        HttpServer second = server("second");
        try {
            runner.run(context -> {
                RestClient client = context.getBean(RestClient.class);
                discover(first);
                assertThat(client.post().uri("/oauth2/token").retrieve().body(String.class)).isEqualTo("first");
                discover(second);
                assertThat(client.post().uri("/oauth2/token").retrieve().body(String.class)).isEqualTo("second");
            });
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    private void discover(HttpServer server) {
        when(discovery.getInstances("iam-service")).thenReturn(List.of(new DefaultServiceInstance(
                "iam-test", "iam-service", "127.0.0.1", server.getAddress().getPort(), false)));
    }

    @Test
    void failsWithoutHealthyInstancesInsteadOfUsingTheStaticAddress() {
        when(discovery.getInstances("iam-service")).thenReturn(List.of());
        runner.run(context -> assertThatThrownBy(() -> context.getBean(RestClient.class)
                .post().uri("/oauth2/token").retrieve().body(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有健康的 iam-service 实例"));
    }

    @Test
    void failsWhenDiscoveryIsUnavailableInsteadOfUsingTheStaticAddress() {
        when(discovery.getInstances("iam-service")).thenThrow(new IllegalStateException("registry unavailable"));
        runner.run(context -> assertThatThrownBy(() -> context.getBean(RestClient.class)
                .post().uri("/oauth2/token").retrieve().body(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable"));
    }

    @Test
    void keepsTheExistingClientConfigurationOutsideLocalDevelopment() throws Exception {
        HttpServer existing = server("existing");
        try {
            runner.withPropertyValues("spring.profiles.active=production",
                            "saasforge.iam.http-base-url=http://127.0.0.1:" + existing.getAddress().getPort())
                    .run(context -> assertThat(context.getBean(RestClient.class)
                            .post().uri("/oauth2/token").retrieve().body(String.class)).isEqualTo("existing"));
        } finally {
            existing.stop(0);
        }
    }

    private static HttpServer server(String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }
}

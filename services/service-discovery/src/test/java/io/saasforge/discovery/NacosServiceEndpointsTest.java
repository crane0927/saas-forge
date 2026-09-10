package io.saasforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class NacosServiceEndpointsTest {
    @Test
    void httpClientFollowsRegisteredPortWithoutChangingCallerConfiguration() throws Exception {
        NamingService naming = mock(NamingService.class);
        HttpServer first = server("first");
        HttpServer second = server("second");
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP")) {
            when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                    .thenReturn(List.of(instance(first.getAddress().getPort())))
                    .thenReturn(List.of(instance(second.getAddress().getPort())));
            var client = endpoints.httpClient("iam-service");
            assertThat(client.get().uri("/.well-known/jwks.json").retrieve().body(String.class)).isEqualTo("first");
            assertThat(client.get().uri("/.well-known/jwks.json").retrieve().body(String.class)).isEqualTo("second");
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    @Test
    void httpRejectsEmptyRegistryAndDiscoveryFailureAfterSuccessfulRequest() throws Exception {
        NamingService naming = mock(NamingService.class);
        HttpServer server = server("available");
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP")) {
            when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                    .thenReturn(List.of(instance(server.getAddress().getPort())))
                    .thenReturn(List.of())
                    .thenThrow(new com.alibaba.nacos.api.exception.NacosException(500, "offline"));
            var client = endpoints.httpClient("iam-service");
            assertThat(client.get().uri("/.well-known/jwks.json").retrieve().body(String.class)).isEqualTo("available");
            assertThatThrownBy(() -> client.get().uri("/.well-known/jwks.json").retrieve().body(String.class))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("No healthy instance");
            assertThatThrownBy(() -> client.get().uri("/.well-known/jwks.json").retrieve().body(String.class))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Service discovery unavailable");
        } finally {
            server.stop(0);
        }
    }

    static Instance instance(int port) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(port);
        instance.setHealthy(true);
        instance.setEnabled(true);
        return instance;
    }

    static HttpServer server(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}

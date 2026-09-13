package io.saasforge.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 本地联调按次读取健康实例；不使用订阅缓存，发现失败不能继续访问旧地址。 */
public final class NacosServiceEndpoints implements AutoCloseable {
    private final NamingService naming;
    private final String group;
    // 无排队的有界工作池：注册表慢请求不能阻塞调用线程，也不能无限积累本地查询线程。
    private final java.util.concurrent.ThreadPoolExecutor queries = new java.util.concurrent.ThreadPoolExecutor(
            0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "local-nacos-query");
                thread.setDaemon(true);
                return thread;
            });

    public NacosServiceEndpoints(NamingService naming, String group) {
        this.naming = naming;
        this.group = group;
    }

    Instance select(String serviceId) {
        return select(serviceId, java.util.concurrent.TimeUnit.SECONDS.toNanos(3));
    }

    Instance select(String serviceId, long remainingNanos) {
        if (remainingNanos <= 0) throw new IllegalStateException("Service discovery deadline exceeded");
        java.util.concurrent.Future<Instance> query = queries.submit(() -> query(serviceId));
        try {
            return query.get(remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service discovery interrupted for " + serviceId, exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("Service discovery unavailable for " + serviceId, exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Service discovery deadline exceeded for " + serviceId, exception);
        } finally {
            query.cancel(true);
        }
    }

    private Instance query(String serviceId) {
        try {
            return naming.selectInstances(serviceId, group, true, false).stream()
                    .filter(instance -> instance.isHealthy() && instance.isEnabled() && instance.getWeight() > 0)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No healthy instance for " + serviceId));
        } catch (NacosException exception) {
            throw new IllegalStateException("Service discovery unavailable for " + serviceId, exception);
        }
    }

    @Override
    public void close() {
        queries.shutdownNow();
    }

    public RestClient httpClient(String serviceId) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl("http://" + serviceId).requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    Instance instance = select(serviceId);
                    URI target = UriComponentsBuilder.fromUri(request.getURI())
                            .host(instance.getIp()).port(instance.getPort()).build(true).toUri();
                    HttpRequest resolved = new HttpRequestWrapper(request) {
                        @Override
                        public URI getURI() { return target; }
                    };
                    return execution.execute(resolved, body);
                }).build();
    }
}

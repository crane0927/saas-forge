package io.saasforge.starter.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

/** 单实例单次刷新；缓存失败不续期，未知 kid 不创建随输入增长的负缓存。 */
final class IamJwksKeyResolver implements ServiceJwtVerificationKeyResolver, AutoCloseable {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final LoadBalancerClient loadBalancer;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Duration waitTimeout;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "saasforge-jwks");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Instant.MIN);
    private Instant nextRefresh = Instant.MIN;
    private CompletableFuture<Snapshot> inFlight;

    IamJwksKeyResolver(LoadBalancerClient loadBalancer) {
        this(loadBalancer, Clock.systemUTC());
    }

    IamJwksKeyResolver(LoadBalancerClient loadBalancer, Clock clock) {
        this(loadBalancer, clock, Duration.ofSeconds(10), Duration.ofSeconds(2));
    }

    IamJwksKeyResolver(LoadBalancerClient loadBalancer, Clock clock, Duration refreshInterval, Duration waitTimeout) {
        if (refreshInterval == null || refreshInterval.toMillis() < 1 || waitTimeout == null || waitTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("JWKS 刷新间隔与等待上限必须为正毫秒数");
        }
        this.loadBalancer = loadBalancer;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
        this.waitTimeout = waitTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(waitTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Optional<ServiceJwtVerificationKey> findByKid(String kid) {
        Snapshot current = snapshot;
        if (clock.instant().isBefore(current.expiresAt()) && current.keys().containsKey(kid)) {
            return Optional.of(current.keys().get(kid));
        }
        return Optional.ofNullable(refresh().keys().get(kid));
    }

    boolean isReady() {
        Snapshot current = snapshot;
        return !(clock.instant().isBefore(current.expiresAt()) ? current : refresh()).keys().isEmpty();
    }

    private Snapshot refresh() {
        CompletableFuture<Snapshot> pending;
        synchronized (this) {
            Instant now = clock.instant();
            if (inFlight != null && !inFlight.isDone()) {
                pending = inFlight;
            } else if (now.isBefore(nextRefresh)) {
                if (inFlight != null && !inFlight.isCompletedExceptionally()
                        && now.isBefore(snapshot.expiresAt())) {
                    return snapshot;
                }
                throw new IamJwksUnavailableException(null);
            } else {
                nextRefresh = now.plus(refreshInterval);
                pending = CompletableFuture.supplyAsync(() -> fetch(now), executor);
                inFlight = pending;
            }
        }
        try {
            Snapshot result = pending.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!clock.instant().isBefore(result.expiresAt())) {
                throw new IamJwksUnavailableException(null);
            }
            return result;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IamJwksUnavailableException(exception);
        }
    }

    private Snapshot fetch(Instant startedAt) {
        try {
            var instance = loadBalancer.choose("iam-service");
            if (instance == null) {
                throw new IllegalStateException("IAM 无健康实例");
            }
            var request = HttpRequest.newBuilder(instance.getUri().resolve("/.well-known/jwks.json"))
                    .timeout(waitTimeout).GET().build();
            var responseFuture = http.sendAsync(request, response -> new LimitedJwksBodySubscriber());
            HttpResponse<String> response;
            try {
                response = responseFuture.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                responseFuture.cancel(true);
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("IAM JWKS 获取失败");
            }
            Map<String, ServiceJwtVerificationKey> keys = new HashMap<>();
            var published = JWKSet.parse(response.body()).getKeys();
            if (published.isEmpty() || published.size() > 64) {
                throw new IllegalStateException("IAM JWKS 密钥数量不合法");
            }
            for (var candidate : published) {
                if (!(candidate instanceof RSAKey rsa) || !JWSAlgorithm.RS256.equals(candidate.getAlgorithm())
                        || !KeyUse.SIGNATURE.equals(candidate.getKeyUse()) || candidate.isPrivate()
                        || candidate.getKeyID() == null || candidate.getKeyID().isBlank() || rsa.size() < 2048) {
                    throw new IllegalStateException("IAM JWKS 公钥不合法");
                }
                var key = new ServiceJwtVerificationKey(rsa.getKeyID(), rsa.getModulus().toString(), rsa.getPublicExponent().toString());
                if (keys.putIfAbsent(key.kid(), key) != null) {
                    throw new IllegalStateException("IAM JWKS 存在重复 kid");
                }
            }
            Snapshot replacement = new Snapshot(Map.copyOf(keys), startedAt.plus(CACHE_TTL));
            snapshot = replacement;
            return replacement;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IamJwksUnavailableException(exception);
        }
    }

    @Override
    public void close() { executor.shutdownNow(); }

    private record Snapshot(Map<String, ServiceJwtVerificationKey> keys, Instant expiresAt) { }
}

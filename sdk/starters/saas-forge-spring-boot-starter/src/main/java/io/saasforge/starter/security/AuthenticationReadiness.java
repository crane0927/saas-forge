package io.saasforge.starter.security;

import java.util.HashMap;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.Ordered;

/** 认证依赖仅加入就绪检查，不将短暂故障变为 Liveness 重启。 */
final class AuthenticationReadiness implements HealthIndicator, HealthEndpointGroupsPostProcessor, Ordered {
    static final String NAME = "saasForgeAuthentication";
    private final IamJwksKeyResolver keys;
    private final RedisReceiverTokenRevocationChecker revocations;

    AuthenticationReadiness(IamJwksKeyResolver keys, RedisReceiverTokenRevocationChecker revocations) {
        this.keys = keys;
        this.revocations = revocations;
    }

    @Override
    public Health health() {
        try {
            return keys.isReady() && revocations.isReady() ? Health.up().build() : Health.down().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
        var additional = new HashMap<String, HealthEndpointGroup>();
        for (String name : groups.getNames()) {
            additional.put(name, groups.get(name));
        }
        var existing = groups.get("readiness");
        additional.put("readiness", new ReadinessGroup(existing == null ? groups.getPrimary() : existing, existing != null));
        return HealthEndpointGroups.of(groups.getPrimary(), additional);
    }

    private record ReadinessGroup(HealthEndpointGroup delegate, boolean existing) implements HealthEndpointGroup {
        @Override
        public boolean isMember(String name) {
            return NAME.equals(name) || (existing ? delegate.isMember(name) : "readinessState".equals(name));
        }
        @Override
        public boolean showComponents(SecurityContext context) { return delegate.showComponents(context); }
        @Override
        public boolean showDetails(SecurityContext context) { return delegate.showDetails(context); }
        @Override
        public StatusAggregator getStatusAggregator() { return delegate.getStatusAggregator(); }
        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() { return delegate.getHttpCodeStatusMapper(); }
        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() { return existing ? delegate.getAdditionalPath() : null; }
    }
}

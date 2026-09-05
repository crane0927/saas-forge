package io.saasforge.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LocalGatewayReplacementLoadBalancerConfigurationTest {

    @Test
    void mapsFormalServiceIdToOnlyTheConfiguredLoopbackPort() {
        var supplier = LocalGatewayReplacementLoadBalancerConfiguration.supplier(
                LocalGatewayReplacementLoadBalancerConfiguration.TENANT_ACCESS_SERVICE_ID, 8082);

        var instance = supplier.get().blockFirst().get(0);

        assertEquals("tenant-access-service", supplier.getServiceId());
        assertEquals("127.0.0.1", instance.getHost());
        assertEquals(8082, instance.getPort());
        assertFalse(instance.isSecure());
    }

    @Test
    void rejectsPortsOutsideTheTcpRange() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalGatewayReplacementLoadBalancerConfiguration.supplier("iam-service", 0));
        assertThrows(IllegalArgumentException.class,
                () -> LocalGatewayReplacementLoadBalancerConfiguration.supplier("iam-service", 65536));
    }
}

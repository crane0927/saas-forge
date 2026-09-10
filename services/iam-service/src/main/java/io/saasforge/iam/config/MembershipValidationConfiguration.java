package io.saasforge.iam.config;

import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.iam.application.authentication.MembershipValidation;
import io.saasforge.iam.infrastructure.grpc.GrpcMembershipValidation;
import io.saasforge.iam.infrastructure.security.ReservedIamServiceAccessTokenProvider;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
public class MembershipValidationConfiguration {
    @Bean
    @Profile("!local")
    RestClient iamServiceRestClient(
            @Value("${saasforge.iam.http-base-url:http://iam-service:8080}") String baseUrl) {
        return RestClient.create(baseUrl);
    }

    @Bean
    @Profile("local")
    RestClient localIamServiceRestClient(DiscoveryClient discovery) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(3000);
        return RestClient.builder().baseUrl("http://iam-service")
                .requestFactory(requests)
                .requestInterceptor((request, body, execution) -> {
                    // 每次实际 HTTP 调用重新取健康实例，禁止沿用本地静态地址兜底。
                    var instances = discovery.getInstances("iam-service");
                    if (instances.isEmpty()) {
                        throw new IllegalStateException("Nacos 中没有健康的 iam-service 实例");
                    }
                    var instance = instances.get(0);
                    URI target = UriComponentsBuilder.fromUri(request.getURI())
                            .scheme(instance.isSecure() ? "https" : "http")
                            .host(instance.getHost()).port(instance.getPort()).build(true).toUri();
                    return execution.execute(new HttpRequestWrapper(request) {
                        @Override
                        public URI getURI() {
                            return target;
                        }
                    }, body);
                }).build();
    }

    @Bean
    ReservedIamServiceAccessTokenProvider reservedIamServiceAccessTokenProvider(
            RestClient iamServiceRestClient,
            Clock clock,
            @Value("${saasforge.iam.service-client-id-file}") String clientIdFile,
            @Value("${saasforge.iam.service-client-secret-file}") String clientSecretFile) {
        return new ReservedIamServiceAccessTokenProvider(
                iamServiceRestClient, Path.of(clientIdFile), Path.of(clientSecretFile), clock);
    }

    @Bean
    @ConditionalOnMissingBean(MembershipValidation.class)
    MembershipValidation membershipValidation(
            GrpcChannelFactory channels,
            ReservedIamServiceAccessTokenProvider serviceTokens,
            @Value("${saasforge.iam.tenant-access-grpc-target:tenant-access}") String target) {
        return new GrpcMembershipValidation(
                MembershipValidationServiceGrpc.newBlockingStub(channels.createChannel(target)),
                serviceTokens::membershipReadToken);
    }
}

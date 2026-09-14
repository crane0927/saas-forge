package io.saas.forge.iam.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClient;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.client.OAuthClientStatus;
import io.saas.forge.sdk.auth.ServiceAccessAuthorization;
import io.saas.forge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** 只允许保留的 Tenant Access Client 使用精确 Scope 调用会话撤销契约。 */
@GlobalServerInterceptor
@Component
public final class UserSessionRevocationServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String REQUIRED_SCOPE = "iam:sessions:write";
    private final ServiceAccessTokenAuthorizer tokens;
    private final OAuthClientRepository clients;

    public UserSessionRevocationServerInterceptor(
            ServiceAccessTokenAuthorizer tokens, OAuthClientRepository clients) {
        this.tokens = tokens;
        this.clients = clients;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!UserSessionRevocationServiceGrpc.SERVICE_NAME.equals(
                call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return close(call, Status.UNAUTHENTICATED);
        }
        try {
            ServiceAccessAuthorization authorizationResult = tokens.authorize(
                    authorization.substring("Bearer ".length()), REQUIRED_SCOPE);
            boolean allowed = clients.findById(authorizationResult.clientId())
                    .filter(client -> client.status() == OAuthClientStatus.ACTIVE)
                    .filter(client -> ReservedServiceClient.TENANT_ACCESS.displayName().equals(client.displayName()))
                    .map(client -> client.allowedScopes().equals(ReservedServiceClient.TENANT_ACCESS.allowedScopes()))
                    .orElse(false);
            return allowed ? next.startCall(call, headers) : close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenScopeException exception) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException exception) {
            return close(call, Status.UNAUTHENTICATED);
        }
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}

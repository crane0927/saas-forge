package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.sdk.auth.ServiceAccessAuthorization;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import java.util.UUID;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** 只允许保留的 Tenant Access Client 使用 Password Setup 投递 Scope。 */
@GlobalServerInterceptor
@Component
public final class PasswordSetupDeliveryServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String REQUIRED_SCOPE = "iam:password-setup:write";
    private static final Context.Key<UUID> CALLER_CLIENT_ID = Context.key("password-setup-delivery-caller-client-id");

    private final ServiceAccessTokenAuthorizer tokens;
    private final OAuthClientRepository clients;

    public PasswordSetupDeliveryServerInterceptor(
            ServiceAccessTokenAuthorizer tokens, OAuthClientRepository clients) {
        this.tokens = tokens;
        this.clients = clients;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!PasswordSetupServiceGrpc.SERVICE_NAME.equals(call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return close(call, Status.UNAUTHENTICATED);
        }
        try {
            ServiceAccessAuthorization authorizationResult = tokens.authorize(
                    authorization.substring("Bearer ".length()), REQUIRED_SCOPE);
            if (!isTenantAccessClient(authorizationResult.clientId())) {
                return close(call, Status.PERMISSION_DENIED);
            }
            return Contexts.interceptCall(
                    Context.current().withValue(CALLER_CLIENT_ID, authorizationResult.clientId()),
                    call,
                    headers,
                    next);
        } catch (ServiceAccessTokenScopeException exception) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException exception) {
            return close(call, Status.UNAUTHENTICATED);
        }
    }

    static UUID callerClientId() {
        return CALLER_CLIENT_ID.get();
    }

    private boolean isTenantAccessClient(UUID clientId) {
        return clients.findById(clientId)
                .filter(client -> client.status() == OAuthClientStatus.ACTIVE)
                .filter(client -> ReservedServiceClient.TENANT_ACCESS.displayName().equals(client.displayName()))
                .map(OAuthClient::allowedScopes)
                .filter(ReservedServiceClient.TENANT_ACCESS.allowedScopes()::equals)
                .isPresent();
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}

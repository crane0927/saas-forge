package io.saasforge.entitlement.infrastructure.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.sdk.auth.ServiceAccessAuthorization;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import java.util.UUID;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** 该内部边界只接受 IAM 唯一授予 entitlement:quota:write 的 Tenant Access Service Token。 */
@GlobalServerInterceptor
@Component
public final class QuotaCommandServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String REQUIRED_SCOPE = "entitlement:quota:write";
    private static final Context.Key<UUID> CALLER_CLIENT_ID = Context.key("quota-command-caller-client-id");

    private final ServiceAccessTokenAuthorizer tokens;

    public QuotaCommandServerInterceptor(ServiceAccessTokenAuthorizer tokens) {
        this.tokens = tokens;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!QuotaCommandServiceGrpc.SERVICE_NAME.equals(
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
            Context context = Context.current().withValue(CALLER_CLIENT_ID, authorizationResult.clientId());
            return Contexts.interceptCall(context, call, headers, next);
        } catch (ServiceAccessTokenScopeException exception) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException exception) {
            return close(call, Status.UNAUTHENTICATED);
        }
    }

    static UUID callerClientId() {
        return CALLER_CLIENT_ID.get();
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}

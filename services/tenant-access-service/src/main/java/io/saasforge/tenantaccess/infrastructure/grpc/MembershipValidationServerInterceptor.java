package io.saasforge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceClientId;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** Membership Validation 只接受保留 IAM Client 的精确 Membership 读取 Scope。 */
@GlobalServerInterceptor
@Component
public final class MembershipValidationServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String REQUIRED_SCOPE = "tenant-access:membership:read";

    private final ServiceAccessTokenVerifier tokens;
    private final IamServiceClientId iamClientId;

    public MembershipValidationServerInterceptor(
            ServiceAccessTokenVerifier tokens,
            IamServiceClientId iamClientId) {
        this.tokens = tokens;
        this.iamClientId = iamClientId;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!MembershipValidationServiceGrpc.SERVICE_NAME.equals(
                call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return close(call, Status.UNAUTHENTICATED);
        }
        try {
            tokens.verify(
                    authorization.substring("Bearer ".length()),
                    iamClientId.value(),
                    REQUIRED_SCOPE);
            return next.startCall(call, headers);
        } catch (ServiceAccessTokenScopeException exception) {
            return close(call, Status.PERMISSION_DENIED);
        } catch (ServiceAccessTokenInvalidException exception) {
            return close(call, Status.UNAUTHENTICATED);
        } catch (RuntimeException exception) {
            return close(call, Status.UNAVAILABLE);
        }
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
    }
}

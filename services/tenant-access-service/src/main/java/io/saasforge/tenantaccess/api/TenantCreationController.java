package io.saasforge.tenantaccess.api;

import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saasforge.tenantaccess.application.tenant.CreatePendingTenantService;
import io.saasforge.tenantaccess.application.tenant.TenantCreationResult;
import io.saasforge.tenantaccess.contract.api.PlatformTenantsApi;
import io.saasforge.tenantaccess.contract.model.CreateTenantRequest;
import io.saasforge.tenantaccess.contract.model.AdministratorInitializationRequest;
import io.saasforge.tenantaccess.contract.model.Tenant;
import io.saasforge.tenantaccess.contract.model.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class TenantCreationController implements PlatformTenantsApi {
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final PlatformAdminAuthorizer authorizer;
    private final CreatePendingTenantService tenantCreation;
    private final InitializeTenantAdministratorService administratorInitialization;
    private final ResendAdministratorPasswordSetupService administratorPasswordSetup;

    public TenantCreationController(
            PlatformAdminAuthorizer authorizer,
            CreatePendingTenantService tenantCreation,
            InitializeTenantAdministratorService administratorInitialization,
            ResendAdministratorPasswordSetupService administratorPasswordSetup) {
        this.authorizer = authorizer;
        this.tenantCreation = tenantCreation;
        this.administratorInitialization = administratorInitialization;
        this.administratorPasswordSetup = administratorPasswordSetup;
    }

    @Override
    public ResponseEntity<Void> resendTenantAdministratorPasswordSetup(
            UUID tenantId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        administratorPasswordSetup.resend(
                actorIdentityId, idempotencyKey, tenantId, traceId(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Tenant> initializeTenantAdministrator(
            UUID tenantId,
            UUID idempotencyKey,
            AdministratorInitializationRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        TenantAdministratorInitializationResult result = administratorInitialization.initialize(
                actorIdentityId,
                idempotencyKey,
                tenantId,
                request.getAdministratorEmail(),
                request.getAdministratorDisplayName(),
                traceId(httpRequest));
        return ResponseEntity.ok(toResponse(result));
    }

    @Override
    public ResponseEntity<Tenant> createPlatformTenant(
            UUID idempotencyKey, CreateTenantRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        TenantCreationResult result = tenantCreation.create(
                actorIdentityId,
                idempotencyKey,
                request.getDisplayName(),
                request.getExpiresAt() == null ? null : request.getExpiresAt().toInstant(),
                traceId(httpRequest));
        return ResponseEntity.status(201).body(toResponse(result));
    }

    private static Tenant toResponse(TenantCreationResult result) {
        return new Tenant(
                result.id(),
                result.displayName(),
                TenantStatus.valueOf(result.status().name()),
                asUtc(result.expiresAt()),
                result.createdAt().atOffset(ZoneOffset.UTC),
                result.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static Tenant toResponse(TenantAdministratorInitializationResult result) {
        return new Tenant(
                result.id(),
                result.displayName(),
                TenantStatus.valueOf(result.status().name()),
                asUtc(result.expiresAt()),
                result.createdAt().atOffset(ZoneOffset.UTC),
                result.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static OffsetDateTime asUtc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Matcher matcher = TRACE_PARENT.matcher(traceparent == null ? "" : traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }
}

package io.saasforge.tenantaccess.api;

import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saasforge.tenantaccess.application.tenant.RecoverableTenantCreationService;
import io.saasforge.tenantaccess.application.tenant.TenantQueryService;
import io.saasforge.tenantaccess.contract.model.TenantPage;
import io.saasforge.tenantaccess.contract.model.TenantCreationRecovery;
import io.saasforge.tenantaccess.contract.model.TenantCreationPage;
import io.saasforge.tenantaccess.application.tenant.TenantCreationResult;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleResult;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class TenantCreationController implements PlatformTenantsApi {
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final PlatformAdminAuthorizer authorizer;
    private final RecoverableTenantCreationService tenantCreation;
    private final InitializeTenantAdministratorService administratorInitialization;
    private final ResendAdministratorPasswordSetupService administratorPasswordSetup;
    private final TenantLifecycleService tenantLifecycle;
    private final TenantQueryService tenantQueries;

    @Autowired
    public TenantCreationController(
            PlatformAdminAuthorizer authorizer,
            RecoverableTenantCreationService tenantCreation,
            InitializeTenantAdministratorService administratorInitialization,
            ResendAdministratorPasswordSetupService administratorPasswordSetup,
            TenantLifecycleService tenantLifecycle,
            TenantQueryService tenantQueries) {
        this.authorizer = authorizer;
        this.tenantCreation = tenantCreation;
        this.administratorInitialization = administratorInitialization;
        this.administratorPasswordSetup = administratorPasswordSetup;
        this.tenantLifecycle = tenantLifecycle;
        this.tenantQueries = tenantQueries;
    }

    TenantCreationController(
            PlatformAdminAuthorizer authorizer,
            RecoverableTenantCreationService tenantCreation,
            InitializeTenantAdministratorService administratorInitialization,
            ResendAdministratorPasswordSetupService administratorPasswordSetup,
            TenantLifecycleService tenantLifecycle) {
        this(authorizer, tenantCreation, administratorInitialization, administratorPasswordSetup, tenantLifecycle, null);
    }

    TenantCreationController(
            PlatformAdminAuthorizer authorizer,
            RecoverableTenantCreationService tenantCreation,
            InitializeTenantAdministratorService administratorInitialization,
            ResendAdministratorPasswordSetupService administratorPasswordSetup) {
        this(authorizer, tenantCreation, administratorInitialization, administratorPasswordSetup, null);
    }

    @Override
    public ResponseEntity<TenantCreationPage> listTenantCreations(String cursor, Integer limit) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = tenantCreation.list(actor, cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new TenantCreationPage(page.items().stream().map(TenantCreationController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<TenantCreationRecovery> getTenantCreation(UUID creationId) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return recoveryResponse(tenantCreation.get(actor, creationId));
    }

    @Override
    public ResponseEntity<TenantCreationRecovery> recoverTenantCreation(UUID creationId, UUID idempotencyKey, java.util.Map<String, Object> requestBody) {
        HttpServletRequest request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        return recoveryResponse(tenantCreation.recover(actor, creationId, idempotencyKey, traceId(request)));
    }

    private static ResponseEntity<TenantCreationRecovery> recoveryResponse(
            io.saasforge.tenantaccess.application.tenant.TenantCreationRecovery result) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(toResponse(result));
    }

    private static TenantCreationRecovery toResponse(
            io.saasforge.tenantaccess.application.tenant.TenantCreationRecovery result) {
        return new TenantCreationRecovery(result.id(), result.displayName(),
                TenantCreationRecovery.StateEnum.valueOf(result.state().name()),
                asUtc(result.createdAt()), asUtc(result.replayUntil()), result.canReplay())
                .tenantId(result.tenantId()).idempotencyKey(result.idempotencyKey());
    }

    @Override
    public ResponseEntity<TenantPage> listPlatformTenants(String cursor, Integer limit, String name, TenantStatus status) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = tenantQueries.list(name,
                status == null ? null : io.saasforge.tenantaccess.domain.tenant.TenantStatus.valueOf(status.name()),
                cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new TenantPage(page.items().stream().map(TenantCreationController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<Tenant> getPlatformTenant(UUID tenantId) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(tenantQueries.get(tenantId)));
    }

    private static Tenant toResponse(io.saasforge.tenantaccess.domain.tenant.Tenant tenant) {
        return new Tenant(tenant.id(), tenant.displayName(), TenantStatus.valueOf(tenant.status().name()),
                asUtc(tenant.expiresAt()), asUtc(tenant.createdAt()), asUtc(tenant.updatedAt()));
    }

    @Override
    public ResponseEntity<Tenant> suspendTenant(UUID tenantId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toResponse(tenantLifecycle.suspend(
                actorIdentityId, idempotencyKey, tenantId, traceId(httpRequest))));
    }

    @Override
    public ResponseEntity<Tenant> resumeTenant(UUID tenantId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toResponse(tenantLifecycle.resume(actorIdentityId, idempotencyKey, tenantId)));
    }

    @Override
    public ResponseEntity<Tenant> recoverTenantSuspension(UUID tenantId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actorIdentityId = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toResponse(tenantLifecycle.recoverSuspension(
                actorIdentityId, idempotencyKey, tenantId, traceId(httpRequest))));
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

    private static Tenant toResponse(TenantLifecycleResult result) {
        return new Tenant(
                result.id(), result.displayName(), TenantStatus.valueOf(result.status().name()),
                asUtc(result.expiresAt()), result.createdAt().atOffset(ZoneOffset.UTC),
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

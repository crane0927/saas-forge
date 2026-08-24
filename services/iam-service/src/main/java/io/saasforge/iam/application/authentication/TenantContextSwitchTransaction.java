package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class TenantContextSwitchTransaction {
    private final TenantContextSwitchRepository workflows;
    private final RefreshTokenFamilyRepository families;

    public TenantContextSwitchTransaction(
            TenantContextSwitchRepository workflows,
            RefreshTokenFamilyRepository families) {
        this.workflows = workflows;
        this.families = families;
    }

    @Transactional
    public void rejectCurrent(UUID workflowId, Sha256Digest refreshTokenDigest, Instant rejectedAt) {
        families.revokeForAuthorizationLoss(refreshTokenDigest, rejectedAt);
        workflows.complete(workflowId, TenantContextSwitchStatus.CURRENT_REJECTED, rejectedAt);
    }

    @Transactional
    public void complete(UUID workflowId, TenantContextSwitchStatus status, Instant completedAt) {
        workflows.complete(workflowId, status, completedAt);
    }
}

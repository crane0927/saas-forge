package io.saasforge.iam.domain.authorization;

import java.time.Instant;
import java.util.UUID;

public interface PlatformRoleAssignmentRepository {

    PlatformRoleAssignment grant(PlatformRoleAssignment assignment);

    boolean hasActiveAssignment(UUID identityId, String roleKey, Instant at);
}

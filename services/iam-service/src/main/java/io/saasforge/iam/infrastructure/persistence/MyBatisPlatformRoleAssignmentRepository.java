package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.infrastructure.persistence.mapper.PlatformRoleAssignmentMapper;
import io.saasforge.iam.infrastructure.persistence.record.PlatformRoleAssignmentRow;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlatformRoleAssignmentRepository implements PlatformRoleAssignmentRepository {
    private final PlatformRoleAssignmentMapper mapper;

    public MyBatisPlatformRoleAssignmentRepository(PlatformRoleAssignmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PlatformRoleAssignment grant(PlatformRoleAssignment assignment) {
        PlatformRoleAssignmentRow row = new PlatformRoleAssignmentRow();
        row.setIdentityId(assignment.identityId());
        row.setRoleKey(assignment.roleKey());
        row.setAssignedAt(IamTime.asOffsetDateTime(assignment.assignedAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(assignment.revokedAt()));
        return toDomain(mapper.insert(row));
    }

    @Override
    public boolean hasActiveAssignment(UUID identityId, Instant at) {
        return mapper.countActive(identityId, IamTime.asOffsetDateTime(at)) > 0;
    }

    private static PlatformRoleAssignment toDomain(PlatformRoleAssignmentRow row) {
        return new PlatformRoleAssignment(row.getId(), row.getIdentityId(), row.getRoleKey(),
                IamTime.asInstant(row.getAssignedAt()), IamTime.asInstant(row.getRevokedAt()));
    }
}

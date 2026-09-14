package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapState;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.infrastructure.persistence.mapper.PlatformAdminBootstrapMapper;
import io.saasforge.iam.infrastructure.persistence.record.PlatformAdminBootstrapStateRow;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlatformAdminBootstrapRepository implements PlatformAdminBootstrapRepository {
    private final PlatformAdminBootstrapMapper mapper;

    public MyBatisPlatformAdminBootstrapRepository(PlatformAdminBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockInitialization() {
        if (mapper.lockInitialization() != 1) {
            throw new IllegalStateException("Platform Admin 引导锁获取失败");
        }
    }

    @Override
    public Optional<PlatformAdminBootstrapState> findState() {
        return Optional.ofNullable(mapper.findState()).map(MyBatisPlatformAdminBootstrapRepository::toDomain);
    }

    @Override
    public boolean hasUntrackedBootstrapState() {
        return mapper.countUntrackedBootstrapState() != 0;
    }

    @Override
    public void create(PlatformAdminBootstrapFact fact) {
        if (mapper.insert(fact.identityId(), fact.credentialId(), fact.roleAssignmentId(), fact.eventId(),
                IamTime.asOffsetDateTime(fact.initializedAt())) != 1) {
            throw new IllegalStateException("Platform Admin 引导事实保存失败");
        }
    }

    private static PlatformAdminBootstrapState toDomain(PlatformAdminBootstrapStateRow row) {
        PlatformAdminBootstrapFact fact = new PlatformAdminBootstrapFact(
                row.getFactIdentityId(), row.getFactCredentialId(), row.getFactRoleAssignmentId(),
                row.getFactEventId(), IamTime.asInstant(row.getInitializedAt()));
        Identity identity = Identity.restore(
                row.getFactIdentityId(), new NormalizedEmail(row.getNormalizedEmail()), row.getDisplayName(),
                IamTime.asInstant(row.getIdentityCreatedAt()));
        PasswordCredential credential = PasswordCredential.restore(
                row.getFactCredentialId(), row.getFactIdentityId(), CredentialType.valueOf(row.getCredentialType()),
                Argon2idPasswordHash.of(row.getPasswordHash()), IamTime.asInstant(row.getCredentialIssuedAt()),
                IamTime.asInstant(row.getCredentialExpiresAt()), IamTime.asInstant(row.getCredentialInvalidatedAt()));
        PlatformRoleAssignment role = new PlatformRoleAssignment(
                row.getFactRoleAssignmentId(), row.getFactIdentityId(), row.getRoleKey(),
                IamTime.asInstant(row.getRoleAssignedAt()), IamTime.asInstant(row.getRoleRevokedAt()));
        return new PlatformAdminBootstrapState(
                fact, identity, credential, role,
                row.getIdentityCredentialCount(), row.getIdentityRoleAssignmentCount());
    }
}

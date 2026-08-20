package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.infrastructure.persistence.mapper.IdentityMapper;
import io.saasforge.iam.infrastructure.persistence.record.CredentialRow;
import io.saasforge.iam.infrastructure.persistence.record.IdentityRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisIdentityRepository implements IdentityRepository {

    private final IdentityMapper mapper;

    public MyBatisIdentityRepository(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Identity save(Identity identity) {
        return toDomain(mapper.insertIdentity(toRow(identity)));
    }

    @Override
    public Optional<Identity> findByEmail(NormalizedEmail email) {
        return Optional.ofNullable(mapper.findIdentityByEmail(email.value())).map(MyBatisIdentityRepository::toDomain);
    }

    @Override
    public PasswordCredential save(PasswordCredential credential) {
        if (credential.type() == CredentialType.PASSWORD
                && mapper.hasValidRegularPassword(credential.identityId()) != 0) {
            throw new IllegalStateException("Identity 已有有效的常规密码凭据");
        }
        return toDomain(mapper.insertCredential(toRow(credential)));
    }

    @Override
    public void invalidate(UUID credentialId, Instant invalidatedAt) {
        if (mapper.invalidateCredential(credentialId, IamTime.asOffsetDateTime(invalidatedAt)) != 1) {
            throw new IllegalStateException("密码凭据不存在或已失效");
        }
    }

    @Override
    public List<PasswordCredential> findCredentials(UUID identityId) {
        return mapper.findCredentialsByIdentityId(identityId).stream().map(MyBatisIdentityRepository::toDomain).toList();
    }

    private static IdentityRow toRow(Identity identity) {
        IdentityRow row = new IdentityRow();
        row.setNormalizedEmail(identity.email().value());
        row.setDisplayName(identity.displayName());
        row.setCreatedAt(IamTime.asOffsetDateTime(identity.createdAt()));
        return row;
    }

    private static CredentialRow toRow(PasswordCredential credential) {
        CredentialRow row = new CredentialRow();
        row.setIdentityId(credential.identityId());
        row.setCredentialType(credential.type().name());
        row.setPasswordHash(credential.passwordHash().encoded());
        row.setIssuedAt(IamTime.asOffsetDateTime(credential.issuedAt()));
        row.setExpiresAt(IamTime.asOffsetDateTime(credential.expiresAt()));
        row.setInvalidatedAt(IamTime.asOffsetDateTime(credential.invalidatedAt()));
        return row;
    }

    private static Identity toDomain(IdentityRow row) {
        return Identity.restore(row.getId(), new NormalizedEmail(row.getNormalizedEmail()), row.getDisplayName(),
                IamTime.asInstant(row.getCreatedAt()));
    }

    private static PasswordCredential toDomain(CredentialRow row) {
        return PasswordCredential.restore(row.getId(), row.getIdentityId(), CredentialType.valueOf(row.getCredentialType()),
                Argon2idPasswordHash.of(row.getPasswordHash()), IamTime.asInstant(row.getIssuedAt()),
                IamTime.asInstant(row.getExpiresAt()), IamTime.asInstant(row.getInvalidatedAt()));
    }
}

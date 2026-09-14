package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.DuplicateIdentityEmailException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisIdentityRepository implements IdentityRepository {

    private final IdentityMapper mapper;

    public MyBatisIdentityRepository(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Identity create(Identity identity) {
        try {
            return toDomain(mapper.insertIdentity(toRow(identity)));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateIdentityEmailException();
        }
    }

    @Override
    @Transactional
    public Identity findOrCreate(Identity identity) {
        IdentityRow inserted = mapper.insertIdentityIfAbsent(toRow(identity));
        if (inserted != null) {
            return toDomain(inserted);
        }
        return findByEmail(identity.email())
                .orElseThrow(() -> new IllegalStateException("Identity 创建后无法读取"));
    }

    @Override
    public Optional<Identity> findByEmail(NormalizedEmail email) {
        return Optional.ofNullable(mapper.findIdentityByEmail(email.value())).map(MyBatisIdentityRepository::toDomain);
    }

    @Override
    public Optional<Identity> findById(UUID identityId) {
        return Optional.ofNullable(mapper.findIdentityById(identityId)).map(MyBatisIdentityRepository::toDomain);
    }

    @Override
    public PasswordCredential create(PasswordCredential credential) {
        lockIdentity(credential.identityId());
        if (credential.type() == CredentialType.PASSWORD
                && mapper.hasValidRegularPassword(credential.identityId()) != 0) {
            throw new IllegalStateException("Identity 已有有效的常规密码凭据");
        }
        return toDomain(mapper.insertCredential(toRow(credential)));
    }

    @Override
    public Optional<PasswordCredential> createFirstPassword(PasswordCredential credential) {
        if (credential.type() != CredentialType.PASSWORD) {
            throw new IllegalArgumentException("首个 Password Setup Credential 必须是正式密码");
        }
        lockIdentity(credential.identityId());
        if (!mapper.lockCredentialsByIdentityId(credential.identityId()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDomain(mapper.insertCredential(toRow(credential))));
    }

    @Override
    @Transactional
    public PasswordCredential replaceInitialPassword(
            PasswordCredential initialCredential, PasswordCredential passwordCredential) {
        requireInitialPasswordReplacement(initialCredential, passwordCredential);
        CredentialRow replacement = mapper.replaceInitialPassword(initialCredential.id(), toRow(passwordCredential));
        if (replacement == null) {
            throw new IllegalStateException("初始密码凭据不存在或已失效");
        }
        return toDomain(replacement);
    }

    @Override
    public void invalidate(UUID credentialId, Instant invalidatedAt) {
        if (mapper.invalidateCredential(credentialId, IamTime.asOffsetDateTime(invalidatedAt)) != 1) {
            throw new IllegalStateException("密码凭据不存在或已失效");
        }
    }

    @Override
    public List<PasswordCredential> lockCredentials(UUID identityId) {
        return mapper.lockCredentialsByIdentityId(identityId).stream()
                .map(MyBatisIdentityRepository::toDomain)
                .toList();
    }

    @Override
    public List<PasswordCredential> findCredentials(UUID identityId) {
        return mapper.findCredentialsByIdentityId(identityId).stream().map(MyBatisIdentityRepository::toDomain).toList();
    }

    @Override
    public Optional<PasswordCredential> findCredential(UUID credentialId) {
        return Optional.ofNullable(mapper.findCredentialById(credentialId)).map(MyBatisIdentityRepository::toDomain);
    }

    private static IdentityRow toRow(Identity identity) {
        IdentityRow row = new IdentityRow();
        row.setNormalizedEmail(identity.email().value());
        row.setDisplayName(identity.displayName());
        row.setCreatedAt(IamTime.asOffsetDateTime(identity.createdAt()));
        return row;
    }

    @Override
    public void lockIdentity(UUID identityId) {
        if (mapper.lockIdentityById(identityId) == null) {
            throw new IllegalStateException("Identity 不存在");
        }
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

    private static void requireInitialPasswordReplacement(
            PasswordCredential initialCredential, PasswordCredential passwordCredential) {
        if (initialCredential.id() == null
                || initialCredential.type() != CredentialType.INITIAL_PLATFORM_PASSWORD
                || initialCredential.invalidatedAt() != null
                || passwordCredential.type() != CredentialType.PASSWORD
                || passwordCredential.id() != null
                || !initialCredential.identityId().equals(passwordCredential.identityId())
                || passwordCredential.issuedAt().isBefore(initialCredential.issuedAt())) {
            throw new IllegalArgumentException("初始密码凭据替换状态不合法");
        }
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

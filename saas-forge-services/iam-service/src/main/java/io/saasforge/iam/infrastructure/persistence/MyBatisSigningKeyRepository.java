package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.infrastructure.persistence.mapper.SigningKeyMapper;
import io.saasforge.iam.infrastructure.persistence.record.SigningKeyRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisSigningKeyRepository implements SigningKeyRepository {

    private final SigningKeyMapper mapper;

    public MyBatisSigningKeyRepository(SigningKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SigningKey savePublished(SigningKey key) {
        if (key.status() != SigningKeyStatus.PUBLISHED) {
            throw new IllegalArgumentException("只能创建 PUBLISHED Signing Key");
        }
        return toDomain(mapper.insertKey(toRow(key)));
    }

    @Override
    public List<SigningKey> findActiveKeys() {
        return mapper.findActiveKeys().stream().map(MyBatisSigningKeyRepository::toDomain).toList();
    }

    @Override
    public List<SigningKey> findPublishedVerificationKeys() {
        return mapper.findPublishedVerificationKeys().stream().map(MyBatisSigningKeyRepository::toDomain).toList();
    }

    @Override
    public java.util.Optional<SigningKey> findById(UUID keyId) {
        return java.util.Optional.ofNullable(mapper.findById(keyId)).map(MyBatisSigningKeyRepository::toDomain);
    }

    @Override
    @Transactional
    public SigningKey activate(UUID keyId, Instant at) {
        SigningKey key = required(mapper.lockKeyById(keyId));
        SigningKey active = key.activate(at);
        SigningKeyRow currentRow = mapper.lockActiveKey();
        if (currentRow != null) {
            SigningKey retiring = toDomain(currentRow).beginRetirement(at);
            update(retiring);
        }
        update(active);
        return active;
    }

    @Override
    @Transactional
    public SigningKey prepareActiveForIssuance(Duration tokenTtl) {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative() || tokenTtl.getNano() != 0) {
            throw new IllegalArgumentException("Access Token TTL 必须是整秒正数");
        }
        SigningKeyRow row = mapper.prepareActiveForIssuance(tokenTtl.getSeconds());
        if (row == null) {
            throw new IllegalStateException("签发时必须恰好存在一个 ACTIVE Signing Key");
        }
        return toDomain(row);
    }

    @Override
    @Transactional
    public SigningKey retire(UUID keyId, Instant at) {
        SigningKey retired = required(mapper.lockKeyById(keyId)).retire(at);
        update(retired);
        return retired;
    }

    @Override
    @Transactional
    public SigningKey revoke(UUID keyId, Instant at) {
        return revoke(keyId, null, at);
    }

    @Override
    @Transactional
    public SigningKey revoke(UUID keyId, UUID replacementKeyId, Instant at) {
        SigningKey target = required(mapper.lockKeyById(keyId));
        if (target.status() == SigningKeyStatus.REVOKED) {
            return target;
        }
        SigningKey replacement = null;
        if (target.status() == SigningKeyStatus.ACTIVE) {
            if (replacementKeyId == null || replacementKeyId.equals(keyId)) {
                throw new IllegalStateException("撤销 ACTIVE Signing Key 必须提供替代 key");
            }
            replacement = required(mapper.lockKeyById(replacementKeyId)).activate(at);
        } else if (replacementKeyId != null) {
            throw new IllegalArgumentException("只有撤销 ACTIVE Signing Key 才能指定替代 key");
        }
        SigningKey revoked = target.revoke(at);
        update(revoked);
        if (replacement != null) {
            update(replacement);
        }
        return revoked;
    }

    private void update(SigningKey key) {
        if (mapper.updateKey(toRow(key)) != 1) {
            throw new IllegalStateException("Signing Key 更新并发冲突");
        }
    }

    private static SigningKey required(SigningKeyRow row) {
        if (row == null) {
            throw new IllegalArgumentException("Signing Key 不存在");
        }
        return toDomain(row);
    }

    private static SigningKeyRow toRow(SigningKey key) {
        SigningKeyRow row = new SigningKeyRow();
        row.setId(key.id());
        row.setKid(key.kid());
        row.setKeyVersionReference(key.keyVersionReference());
        row.setPublicJwkModulus(key.publicJwkModulus());
        row.setPublicJwkExponent(key.publicJwkExponent());
        row.setKeyStatus(key.status().name());
        row.setMaxIssuedTokenTtlSeconds(key.maxIssuedTokenTtl().getSeconds());
        row.setPublishedAt(IamTime.asOffsetDateTime(key.publishedAt()));
        row.setActivatedAt(IamTime.asOffsetDateTime(key.activatedAt()));
        row.setRetiringAt(IamTime.asOffsetDateTime(key.retiringAt()));
        row.setRetireAfter(IamTime.asOffsetDateTime(key.retireAfter()));
        row.setRetiredAt(IamTime.asOffsetDateTime(key.retiredAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(key.revokedAt()));
        return row;
    }

    private static SigningKey toDomain(SigningKeyRow row) {
        return SigningKey.restore(row.getId(), row.getKid(), row.getKeyVersionReference(), row.getPublicJwkModulus(),
                row.getPublicJwkExponent(), SigningKeyStatus.valueOf(row.getKeyStatus()),
                Duration.ofSeconds(row.getMaxIssuedTokenTtlSeconds()),
                IamTime.asInstant(row.getPublishedAt()), IamTime.asInstant(row.getActivatedAt()),
                IamTime.asInstant(row.getRetiringAt()),
                IamTime.asInstant(row.getRetireAfter()), IamTime.asInstant(row.getRetiredAt()),
                IamTime.asInstant(row.getRevokedAt()));
    }
}

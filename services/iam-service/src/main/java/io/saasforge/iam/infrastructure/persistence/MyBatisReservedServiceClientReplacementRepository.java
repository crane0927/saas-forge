package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.client.ReservedServiceClientReplacement;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacementRepository;
import io.saasforge.iam.domain.client.ReservedServiceKey;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.ReservedServiceClientReplacementMapper;
import io.saasforge.iam.infrastructure.persistence.record.ReservedServiceClientReplacementRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisReservedServiceClientReplacementRepository
        implements ReservedServiceClientReplacementRepository {

    private final ReservedServiceClientReplacementMapper mapper;

    public MyBatisReservedServiceClientReplacementRepository(ReservedServiceClientReplacementMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ReservedServiceClientReplacement> find(UUID replacementRequestId) {
        return Optional.ofNullable(mapper.find(replacementRequestId)).map(MyBatisReservedServiceClientReplacementRepository::toDomain);
    }

    @Override
    public void append(ReservedServiceClientReplacement replacement) {
        ReservedServiceClientReplacementRow row = new ReservedServiceClientReplacementRow();
        row.setReplacementRequestId(replacement.replacementRequestId());
        row.setServiceKey(replacement.serviceKey().name());
        row.setOldClientId(replacement.oldClientId());
        row.setNewClientId(replacement.newClientId());
        row.setRequestFingerprint(replacement.requestFingerprint().value());
        row.setCompletedAt(IamTime.asOffsetDateTime(replacement.completedAt()));
        if (mapper.insert(row) != 1) throw new IllegalStateException("Reserved Service Client Replacement 保存失败");
    }

    private static ReservedServiceClientReplacement toDomain(ReservedServiceClientReplacementRow row) {
        return new ReservedServiceClientReplacement(
                row.getReplacementRequestId(), ReservedServiceKey.valueOf(row.getServiceKey()),
                row.getOldClientId(), row.getNewClientId(), Sha256Digest.of(row.getRequestFingerprint()),
                IamTime.asInstant(row.getCompletedAt()));
    }
}

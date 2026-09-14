package io.saasforge.iam.domain.session;

import java.util.List;
import java.util.UUID;

public record UserSessionRevocationBatch(
        List<UUID> familyIds,
        List<AccessTokenIssuance> issuances,
        UUID nextCursor,
        boolean lastBatch) {

    public UserSessionRevocationBatch {
        familyIds = List.copyOf(familyIds);
        issuances = List.copyOf(issuances);
        if (nextCursor == null && (!familyIds.isEmpty() || !issuances.isEmpty())) {
            throw new IllegalArgumentException("非空撤销批次必须具有稳定游标");
        }
    }
}

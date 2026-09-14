package io.saasforge.iam.domain.client;

import java.util.Optional;
import java.util.UUID;

/** Reserved Service Client 替换请求的永久幂等终态边界。 */
public interface ReservedServiceClientReplacementRepository {

    Optional<ReservedServiceClientReplacement> find(UUID replacementRequestId);

    void append(ReservedServiceClientReplacement replacement);
}

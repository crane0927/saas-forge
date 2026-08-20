package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.CredentialRow;
import io.saasforge.iam.infrastructure.persistence.record.IdentityRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface IdentityMapper {

    IdentityRow insertIdentity(@Param("row") IdentityRow row);

    IdentityRow insertIdentityIfAbsent(@Param("row") IdentityRow row);

    IdentityRow findIdentityByEmail(@Param("normalizedEmail") String normalizedEmail);

    CredentialRow insertCredential(@Param("row") CredentialRow row);

    CredentialRow replaceInitialPassword(
            @Param("initialCredentialId") UUID initialCredentialId,
            @Param("password") CredentialRow password);

    int hasValidRegularPassword(@Param("identityId") UUID identityId);

    int invalidateCredential(@Param("credentialId") UUID credentialId, @Param("invalidatedAt") OffsetDateTime invalidatedAt);

    List<CredentialRow> findCredentialsByIdentityId(@Param("identityId") UUID identityId);
}

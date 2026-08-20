package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.SigningKeyRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface SigningKeyMapper {

    SigningKeyRow insertKey(@Param("row") SigningKeyRow row);

    SigningKeyRow findActiveKey();

    SigningKeyRow lockKeyById(@Param("keyId") UUID keyId);

    SigningKeyRow lockActiveKey();

    int updateKey(@Param("row") SigningKeyRow row);
}

package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.AccessTokenIssuanceRow;
import org.apache.ibatis.annotations.Param;

public interface AccessTokenIssuanceMapper {
    int insert(@Param("row") AccessTokenIssuanceRow row);
}

package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.ReservedServiceClientReplacementRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface ReservedServiceClientReplacementMapper {

    ReservedServiceClientReplacementRow find(@Param("replacementRequestId") UUID replacementRequestId);

    int insert(@Param("row") ReservedServiceClientReplacementRow row);
}

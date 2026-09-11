package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionQueries;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionNotFoundException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.QuotaDefinitionRow;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisQuotaDefinitionQueries implements QuotaDefinitionQueries {
    private final EntitlementBootstrapMapper mapper;
    private final QuotaDefinitionCursor cursors;
    public MyBatisQuotaDefinitionQueries(EntitlementBootstrapMapper mapper, Clock clock) {
        this.mapper = mapper; cursors = new QuotaDefinitionCursor(clock);
    }
    @Override
    public Page list(String code, QuotaDefinitionStatus status, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (code != null && code.length() > 100)) {
            throw new IllegalArgumentException("Invalid filter or page size");
        }
        String filter = code == null ? "" : code;
        String scope = "quota-definitions:" + filter + ":" + status;
        var rows = mapper.listQuotaDefinitions(new EntitlementBootstrapMapper.QuotaQuery(
                filter, status == null ? null : status.name(), cursors.decode(scope, cursor), limit + 1));
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(MyBatisQuotaDefinitionQueries::result).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }
    @Override
    public QuotaDefinitionResult get(UUID id) {
        var row = mapper.findQuotaDefinition(id);
        if (row == null) throw new QuotaDefinitionNotFoundException();
        return result(row);
    }
    private static QuotaDefinitionResult result(QuotaDefinitionRow row) {
        return new QuotaDefinitionResult(row.id(), row.code(), QuotaDefinitionStatus.valueOf(row.status()),
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.updatedAt()));
    }
}

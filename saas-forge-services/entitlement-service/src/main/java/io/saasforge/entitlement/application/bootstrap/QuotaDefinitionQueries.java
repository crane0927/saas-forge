package io.saasforge.entitlement.application.bootstrap;
import java.util.List;
import java.util.UUID;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
public interface QuotaDefinitionQueries {
    record Page(List<QuotaDefinitionResult> items, String nextCursor, boolean hasMore) { }
    Page list(String code, QuotaDefinitionStatus status, String cursor, int limit);
    QuotaDefinitionResult get(UUID id);
}

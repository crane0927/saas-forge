package io.saasforge.entitlement.application.bootstrap;
import java.util.List;
import java.util.UUID;
import io.saasforge.entitlement.domain.plan.PlanStatus;
public interface PlanQueries {
    record Page(List<PlanResult> items, String nextCursor, boolean hasMore) { }
    Page list(String code, PlanStatus status, String cursor, int limit);
    PlanResult get(UUID id);
}

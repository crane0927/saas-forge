package io.saasforge.iam.application.client;

import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.OAuthClientType;
import java.util.List;

/** 平台授权后读取非敏感 Client；游标绑定筛选，失配或过期时拒绝。 */
public interface OAuthClientQueries {
    Page list(String name, OAuthClientType type, OAuthClientStatus status, String cursor, int limit);

    record Page(List<OAuthClient> items, String nextCursor, boolean hasMore) { }
}

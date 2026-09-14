package io.saasforge.iam.domain.client;

/** 新建 OAuth Client 及其初始 Secret 的非敏感持久化结果。 */
public record OAuthClientCreation(OAuthClient client, ClientSecret initialSecret) {
    public OAuthClientCreation {
        if (client == null || initialSecret == null || client.id() == null
                || !client.id().equals(initialSecret.clientId())) {
            throw new IllegalArgumentException("OAuth Client 初始 Secret 关联不合法");
        }
    }
}

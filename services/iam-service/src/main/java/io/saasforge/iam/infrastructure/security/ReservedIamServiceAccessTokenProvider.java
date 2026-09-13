package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.application.authentication.ClientCredentialsTokenService;
import io.saasforge.iam.application.authentication.TenantAccessUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用外部只读 Secret 文件取得 IAM 自身保留 Client 的短期 Service Access Token。 */
public class ReservedIamServiceAccessTokenProvider {
    private static final String MEMBERSHIP_READ_SCOPE = "tenant-access:membership:read";

    private final ClientCredentialsTokenService tokens;
    private final Path clientIdFile;
    private final Path clientSecretFile;
    private final Clock clock;
    private volatile CachedToken membershipReadToken;

    public ReservedIamServiceAccessTokenProvider(
            ClientCredentialsTokenService tokens,
            Path clientIdFile,
            Path clientSecretFile,
            Clock clock) {
        this.tokens = tokens;
        this.clientIdFile = clientIdFile;
        this.clientSecretFile = clientSecretFile;
        this.clock = clock;
    }

    // 保持原 HTTP 请求的事务隔离：密钥 TTL 元数据必须在签名前独立提交。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized String membershipReadToken() {
        try {
            return issueOrReuseToken();
        } catch (RuntimeException exception) {
            // 服务身份签发失败不是浏览器用户凭据错误，也不能当作成员权限被撤销。
            throw new TenantAccessUnavailableException(exception);
        }
    }

    private String issueOrReuseToken() {
        Instant now = clock.instant();
        if (membershipReadToken != null && membershipReadToken.refreshAfter().isAfter(now)) {
            return membershipReadToken.value();
        }
        String clientId = readSecret(clientIdFile);
        UUID parsedClientId = UUID.fromString(clientId);
        if (parsedClientId.version() != 7 || !parsedClientId.toString().equals(clientId)) {
            throw new IllegalStateException("IAM Service Client ID 必须是规范 UUIDv7");
        }
        String clientSecret = readSecret(clientSecretFile);
        var response = tokens.issue(parsedClientId, clientSecret, "client_credentials", MEMBERSHIP_READ_SCOPE);
        if (response.value() == null || response.value().isBlank()
                || response.expiresInSeconds() < 1 || !MEMBERSHIP_READ_SCOPE.equals(response.scope())) {
            throw new IllegalStateException("IAM Service Access Token 响应不合法");
        }
        CachedToken issued = new CachedToken(
                response.value(), now.plusSeconds(Math.max(1, response.expiresInSeconds() - 30L)));
        membershipReadToken = issued;
        return issued.value();
    }

    private static String readSecret(Path path) {
        try {
            String value = Files.readString(path).stripTrailing();
            if (value.isBlank()) {
                throw new IllegalStateException("Service Client Secret 文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Service Client Secret 文件不可读", exception);
        }
    }

    private record CachedToken(String value, Instant refreshAfter) {
    }
}

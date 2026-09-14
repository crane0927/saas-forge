package io.saasforge.iam.application.authentication;

import java.util.Optional;

/** 校验可选 bearer Token；无效或缺失 Token 不改变幂等登出结果。 */
public interface PresentedAccessTokenVerifier {
    Optional<PresentedAccessToken> verify(String authorizationHeader);
}

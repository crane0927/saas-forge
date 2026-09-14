package io.saasforge.iam.application.authentication;

import java.util.List;

public record ContextSelectionLoginResult(
        List<AccessibleMembership> memberships,
        String refreshToken,
        long refreshCookieMaxAgeSeconds) implements LoginResult {

    public ContextSelectionLoginResult {
        memberships = List.copyOf(memberships);
        if (memberships.size() < 2 || memberships.size() > 100) {
            throw new IllegalArgumentException("上下文选择候选数量必须在 2 到 100 之间");
        }
    }
}

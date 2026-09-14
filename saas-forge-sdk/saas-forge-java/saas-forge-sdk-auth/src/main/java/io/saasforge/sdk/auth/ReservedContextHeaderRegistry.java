package io.saasforge.sdk.auth;

import java.util.Locale;
import java.util.Set;

/**
 * 平台保留的身份与授权上下文 Header；这些 Header 只能作为不可信输入被删除或拒绝。
 */
public final class ReservedContextHeaderRegistry {

    private static final Set<String> NAMES = Set.of(
            "x-identity",
            "x-membership",
            "x-tenant-context",
            "x-role",
            "x-permission",
            "x-scope",
            "x-client");

    private ReservedContextHeaderRegistry() {
    }

    public static boolean contains(String headerName) {
        return headerName != null && NAMES.contains(headerName.toLowerCase(Locale.ROOT));
    }
}

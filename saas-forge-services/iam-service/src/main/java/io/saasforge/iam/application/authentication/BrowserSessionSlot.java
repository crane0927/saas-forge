package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;

/** 浏览器 Refresh Token Cookie 槽位；槽位只定位会话，不代替授权事实。 */
public enum BrowserSessionSlot {
    PLATFORM("__Host-sf_platform_refresh"),
    TENANT("__Host-sf_tenant_refresh");

    private final String cookieName;

    BrowserSessionSlot(String cookieName) {
        this.cookieName = cookieName;
    }

    public String cookieName() {
        return cookieName;
    }

    public boolean accepts(RefreshTokenFamilyPurpose purpose) {
        return switch (this) {
            case PLATFORM -> purpose == RefreshTokenFamilyPurpose.USER_PLATFORM
                    || purpose == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE;
            case TENANT -> purpose == RefreshTokenFamilyPurpose.USER_TENANT
                    || purpose == RefreshTokenFamilyPurpose.USER_TENANT_SELECTION;
        };
    }

    public static BrowserSessionSlot forLogin(LoginContextType contextType) {
        return contextType == LoginContextType.PLATFORM ? PLATFORM : TENANT;
    }
}

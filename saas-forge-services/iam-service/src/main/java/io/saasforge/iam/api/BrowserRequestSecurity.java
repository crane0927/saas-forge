package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.BrowserRequestRejectedException;
import io.saasforge.iam.application.authentication.BrowserSessionSlot;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/** IAM 的浏览器入口协议防护；Origin 与槽位配对不代替 Role、Membership 或 Family Purpose。 */
public final class BrowserRequestSecurity {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserRequestSecurity.class);
    private static final Pattern ROOT_DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?[.])"
                    + "+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final String platformOrigin;
    private final String tenantOrigin;

    public BrowserRequestSecurity(String rootDomain) {
        if (rootDomain == null || !ROOT_DOMAIN.matcher(rootDomain).matches()) {
            throw new IllegalArgumentException("browser.rootDomain 必须是规范小写 DNS 根域");
        }
        platformOrigin = "https://platform." + rootDomain;
        tenantOrigin = "https://console." + rootDomain;
    }

    public void requireControlledMutation(
            HttpServletRequest request,
            String csrfHeader,
            BrowserSessionSlot sessionSlot) {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        String contentType = request.getContentType();
        String expectedOrigin = sessionSlot == BrowserSessionSlot.PLATFORM ? platformOrigin : tenantOrigin;
        String reason = null;
        if (!expectedOrigin.equals(origin)) {
            reason = "ORIGIN_SLOT";
        } else if (!"1".equals(csrfHeader)) {
            reason = "CSRF";
        } else if ("cross-site".equalsIgnoreCase(fetchSite)) {
            reason = "FETCH_SITE";
        } else {
            try {
                if (contentType == null
                        || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
                    reason = "CONTENT_TYPE";
                }
            } catch (IllegalArgumentException invalidContentType) {
                reason = "CONTENT_TYPE";
            }
        }
        if (reason != null) {
            LOGGER.warn("Rejected IAM browser request method={} path={} reason={}",
                    request.getMethod(), request.getRequestURI(), reason);
            throw new BrowserRequestRejectedException();
        }
    }
}

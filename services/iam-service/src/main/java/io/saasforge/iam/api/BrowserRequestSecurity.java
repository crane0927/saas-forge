package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.BrowserRequestRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;

/** Tenant Context Switch 的入口协议防护；授权与会话定位仍由应用用例负责。 */
public final class BrowserRequestSecurity {
    private static final Pattern ROOT_DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?[.])"
                    + "+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final Set<String> allowedOrigins;

    public BrowserRequestSecurity(String rootDomain) {
        if (rootDomain == null || !ROOT_DOMAIN.matcher(rootDomain).matches()) {
            throw new IllegalArgumentException("browser.rootDomain 必须是规范小写 DNS 根域");
        }
        allowedOrigins = Set.of("https://platform." + rootDomain, "https://console." + rootDomain);
    }

    public void requireControlledMutation(HttpServletRequest request, String csrfHeader) {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        String contentType = request.getContentType();
        if (!"1".equals(csrfHeader)
                || !allowedOrigins.contains(origin)
                || "cross-site".equalsIgnoreCase(fetchSite)
                || contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            throw new BrowserRequestRejectedException();
        }
    }
}

package io.saasforge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 匿名 Password Setup 仍只允许受控 Console 发起，不能成为跨站密码写入口。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class PasswordSetupBrowserSecurityFilter extends OncePerRequestFilter {
    private static final String PASSWORD_SETUP_PATH = "/api/v1/auth/password-setups";
    private static final String CSRF_HEADER = "X-SF-CSRF";
    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";

    private final Set<String> controlledOrigins;
    private final GatewayProblemDetailsWriter problems;

    PasswordSetupBrowserSecurityFilter(
            @Value("${browser.rootDomain}") String rootDomain,
            GatewayProblemDetailsWriter problems) {
        this.controlledOrigins = Set.of(
                "https://platform." + rootDomain,
                "https://console." + rootDomain);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !PASSWORD_SETUP_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String fetchSite = request.getHeader(FETCH_SITE_HEADER);
        if (origin == null || !controlledOrigins.contains(origin)
                || !"1".equals(request.getHeader(CSRF_HEADER))
                || "cross-site".equalsIgnoreCase(fetchSite)) {
            problems.write(request, response, HttpStatus.FORBIDDEN, "BROWSER_REQUEST_REJECTED",
                    "The browser request is not from a controlled Console origin.");
            return;
        }
        chain.doFilter(request, response);
    }
}

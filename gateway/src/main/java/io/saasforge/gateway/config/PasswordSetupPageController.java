package io.saasforge.gateway.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
final class PasswordSetupPageController {
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; "
            + "script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @GetMapping("/password-setup")
    ResponseEntity<Resource> page() {
        return response("static/password-setup/index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/password-setup/app.js")
    ResponseEntity<Resource> script() {
        return response("static/password-setup/app.js", MediaType.valueOf("text/javascript"));
    }

    @GetMapping("/password-setup/styles.css")
    ResponseEntity<Resource> styles() {
        return response("static/password-setup/styles.css", MediaType.valueOf("text/css"));
    }

    private ResponseEntity<Resource> response(String path, MediaType contentType) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .body(new ClassPathResource(path));
    }
}

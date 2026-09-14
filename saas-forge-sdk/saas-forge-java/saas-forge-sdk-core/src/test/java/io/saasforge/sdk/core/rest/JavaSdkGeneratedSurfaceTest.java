package io.saasforge.sdk.core.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaSdkGeneratedSurfaceTest {

    private static final Set<String> BROWSER_OPERATION_PREFIXES = Set.of(
            "login",
            "refreshAccessToken",
            "changeInitialPassword",
            "establishPassword",
            "selectAuthenticationContext",
            "logout",
            "switchTenantContext");

    @Test
    void generatedJavaApiContainsNoBrowserOperationsOrBrowserManagedInputs() throws Exception {
        Class<?> authenticationApi = Class.forName("io.saasforge.sdk.core.rest.api.AuthenticationApi");
        for (Method method : authenticationApi.getMethods()) {
            assertFalse(BROWSER_OPERATION_PREFIXES.stream().anyMatch(method.getName()::startsWith),
                    method.toString());
            for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                String name = parameter.getName().toLowerCase();
                assertFalse(name.contains("origin")
                                || name.contains("fetch")
                                || name.contains("cookie")
                                || name.contains("refresh"),
                        method.toString());
            }
        }

        assertFalse(classExists("io.saasforge.sdk.core.rest.model.LoginRequest"));
        assertFalse(classExists("io.saasforge.sdk.core.rest.model.PasswordSetupRequest"));
        assertFalse(classExists("io.saasforge.sdk.core.rest.model.SessionSlotRequest"));
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}

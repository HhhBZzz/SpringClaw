package com.springclaw.controller;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AdminPageControllerTest {

    @Test
    void shouldRejectUntrustedFrontendRedirectHost() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AdminPageController("https://evil.example", "localhost,127.0.0.1"));
    }

    @Test
    void shouldRejectFrontendRedirectUrlWithQueryOrFragment() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AdminPageController("http://localhost:5173/?next=https://evil.example", "localhost,127.0.0.1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AdminPageController("http://localhost:5173/#/other", "localhost,127.0.0.1"));
    }

    @Test
    void shouldRedirectOnlyToAllowedVueAdminUrl() {
        AdminPageController controller = new AdminPageController("http://localhost:5173/", "localhost,127.0.0.1");

        Assertions.assertEquals("redirect:http://localhost:5173/#/admin", controller.adminPage());
    }
}

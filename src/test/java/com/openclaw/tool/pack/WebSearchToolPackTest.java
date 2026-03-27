package com.openclaw.tool.pack;

import com.openclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WebSearchToolPackTest {

    @Test
    void fetchUrlTextShouldExplicitlyRedirectToPythonSkill() {
        WebSearchToolPack toolPack = new WebSearchToolPack(
                true,
                "https://example.com?q={query}",
                true,
                5000,
                RestClient.builder().build()
        );

        BusinessException ex = Assertions.assertThrows(
                BusinessException.class,
                () -> toolPack.fetchUrlText("https://example.com")
        );

        Assertions.assertEquals(40090, ex.getCode());
        Assertions.assertTrue(ex.getMessage().contains("Python web skill"));
    }

    @Test
    void webSearchShouldRemainAvailableForLightweightLookup() {
        WebSearchToolPack toolPack = new WebSearchToolPack(
                false,
                "https://example.com?q={query}",
                true,
                5000,
                RestClient.builder().build()
        );

        BusinessException ex = Assertions.assertThrows(
                BusinessException.class,
                () -> toolPack.webSearch("OpenClaw")
        );

        Assertions.assertEquals(40081, ex.getCode());
    }
}

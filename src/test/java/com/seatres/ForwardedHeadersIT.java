package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Behind a TLS-terminating proxy, generated URLs must come back as https, not http. */
class ForwardedHeadersIT extends AbstractPostgresIT {

    private static final Map<String, String> BEHIND_PROXY = Map.of(
            "X-Forwarded-Proto", "https",
            "X-Forwarded-Host", "seatres.example.com");

    @LocalServerPort
    int port;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void theRootRedirectKeepsTheProxysScheme() {
        Api.Response response = api.get("/", null, BEHIND_PROXY);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location").orElseThrow())
                .startsWith("https://seatres.example.com")
                .endsWith("/swagger-ui.html");
    }

    @Test
    void theDocsAdvertiseTheProxysUrlSoTryItOutIsNotBlocked() {
        String serverUrl = api.get("/v3/api-docs", null, BEHIND_PROXY).json()
                .path("servers").get(0).path("url").asText();

        assertThat(serverUrl).isEqualTo("https://seatres.example.com");
    }

    @Test
    void withoutAProxyTheUrlIsUnchanged() {
        assertThat(api.get("/", null).header("Location").orElseThrow())
                .startsWith("http://localhost:" + port);
    }
}

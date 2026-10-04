package com.seatres.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Thin HttpClient wrapper returning status, headers and parsed JSON. */
public final class Api {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final Duration requestTimeout;

    public Api(int port) {
        this(port, DEFAULT_REQUEST_TIMEOUT);
    }

    /** A longer timeout is needed where the server itself waits, such as on a pool timeout. */
    public Api(int port, Duration requestTimeout) {
        this.baseUrl = "http://localhost:" + port;
        this.requestTimeout = requestTimeout;
    }

    public Response get(String path, String bearerToken) {
        return get(path, bearerToken, Map.of());
    }

    public Response get(String path, String bearerToken, Map<String, String> headers) {
        return send(request(path, bearerToken, headers).GET());
    }

    public Response post(String path, String bearerToken, String body) {
        return post(path, bearerToken, body, Map.of());
    }

    public Response post(String path, String bearerToken, String body,
            Map<String, String> headers) {
        return send(request(path, bearerToken, headers)
                .header("Content-Type", "application/json")
                .POST(bodyOf(body)));
    }

    /** No Content-Type at all, for endpoints that must not require one. */
    public Response postWithoutContentType(String path, String bearerToken, String body) {
        return send(request(path, bearerToken, Map.of()).POST(bodyOf(body)));
    }

    public Response postWithContentType(String path, String bearerToken, String body,
            String contentType) {
        return send(request(path, bearerToken, Map.of())
                .header("Content-Type", contentType)
                .POST(bodyOf(body)));
    }

    private HttpRequest.Builder request(String path, String bearerToken,
            Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout);
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        headers.forEach(builder::header);
        return builder;
    }

    private static HttpRequest.BodyPublisher bodyOf(String body) {
        return body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }

    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("not JSON: " + body, e);
            }
        }

        public String code() {
            return json().path("code").asText();
        }

        public String contentType() {
            return header("Content-Type").orElse("");
        }

        public Optional<String> header(String name) {
            return raw.headers().firstValue(name);
        }
    }
}

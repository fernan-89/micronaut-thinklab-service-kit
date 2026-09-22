package com.thinklab.kit.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Minimal blocking HTTP used by the security components; an interface so every failure mode is testable offline. */
public interface HttpTransport {

    /** GET the URL and return the body of a 2xx response; anything else is an {@link IOException}. */
    String get(String url) throws IOException, InterruptedException;

    /** POST a form and return the body of a 2xx response; anything else is an {@link IOException}. */
    String postForm(String url, Map<String, String> form) throws IOException, InterruptedException;

    /** The JDK client implementation with short timeouts. */
    class Default implements HttpTransport {

        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        @Override
        public String get(String url) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build());
        }

        @Override
        public String postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
            String body = form.entrySet().stream()
                    .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        private String send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
            }
            return response.body();
        }
    }
}

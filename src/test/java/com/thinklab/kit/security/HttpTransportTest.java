package com.thinklab.kit.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpTransportTest {

    private HttpServer server;
    private String base;
    private final HttpTransport transport = new HttpTransport.Default();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boom", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("GET returns the body of a 2xx response and rejects anything else")
    void get() throws Exception {
        assertEquals("hello", transport.get(base + "/ok"));
        assertThrows(IOException.class, () -> transport.get(base + "/boom"));
    }

    @Test
    @DisplayName("POST sends the form url-encoded")
    void postForm() throws Exception {
        assertEquals("grant_type=client_credentials&x=a+b%26c",
                transport.postForm(base + "/echo", new java.util.LinkedHashMap<>(Map.of("grant_type", "client_credentials")) {{
                    put("x", "a b&c");
                }}));
        assertThrows(IOException.class, () -> transport.postForm(base + "/boom", Map.of("a", "b")));
    }
}

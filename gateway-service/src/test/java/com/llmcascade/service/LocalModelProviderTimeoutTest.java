package com.llmcascade.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Exercises the actual cold-start timeout path against a real (slow) HTTP
// server, rather than only testing that RouterService reacts correctly once
// ModelUnavailableException is thrown. Uses the JDK's built-in HttpServer so
// no extra test dependency (e.g. WireMock) is needed.
class LocalModelProviderTimeoutTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void slowOllamaResponse_triggersModelUnavailableException() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(500); // longer than the 200ms test timeout below
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"response\":\"too slow\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        LocalModelProvider provider = new LocalModelProvider("http://localhost:" + port, 200);

        assertThatThrownBy(() -> provider.generate("test prompt"))
            .isInstanceOf(ModelUnavailableException.class);
    }

    @Test
    void fastEnoughResponse_returnsNormally() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] body = "{\"response\":\"fast answer\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        LocalModelProvider provider = new LocalModelProvider("http://localhost:" + port, 2000);

        GenerationResult answer = provider.generate("test prompt");

        org.assertj.core.api.Assertions.assertThat(answer.text()).isEqualTo("fast answer");
    }
}





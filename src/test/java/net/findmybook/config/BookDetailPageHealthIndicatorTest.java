package net.findmybook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookDetailPageHealthIndicatorTest {

    @Test
    void should_RequestCanonicalBookRoute_When_TestBookIdIsConfigured() {
        DisposableServer server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.get("/book/healthcheck-book", (request, response) -> response.send()))
            .bindNow();

        try {
            BookDetailPageHealthIndicator indicator = new BookDetailPageHealthIndicator(
                WebClient.builder(), server.port(), "healthcheck-book", true);
            StepVerifier.create(indicator.health())
                .assertNext(health -> assertEquals(Status.UP, health.getStatus()))
                .verifyComplete();
        } finally {
            server.disposeNow();
        }
    }
}

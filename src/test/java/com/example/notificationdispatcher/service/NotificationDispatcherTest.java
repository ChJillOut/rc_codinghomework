package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.exception.DispatchException;
import com.example.notificationdispatcher.model.NotificationMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDispatcherTest {

    private MockWebServer mockWebServer;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        dispatcher = new NotificationDispatcher();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void dispatch_success_makesCorrectRequest() throws Exception {
        // Arrange
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-1")
                .vendorId("ad-system")
                .httpMethod("POST")
                .targetUrl(mockWebServer.url("/api/callback").toString())
                .headers(Map.of("X-Test-Header", "test-val"))
                .body("hello world")
                .build();

        // Act
        dispatcher.dispatch(message);

        // Assert
        RecordedRequest recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/api/callback");
        assertThat(recordedRequest.getHeader("X-Test-Header")).isEqualTo("test-val");
        assertThat(recordedRequest.getBody().readUtf8()).isEqualTo("hello world");
    }

    @Test
    void dispatch_clientError4xx_throwsNonRetryableException() throws Exception {
        // Arrange
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-2")
                .vendorId("ad-system")
                .httpMethod("POST")
                .targetUrl(mockWebServer.url("/api/callback").toString())
                .body("hello")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> dispatcher.dispatch(message))
                .isInstanceOf(DispatchException.class)
                .satisfies(ex -> {
                    DispatchException dex = (DispatchException) ex;
                    assertThat(dex.isRetryable()).isFalse();
                    assertThat(dex.getStatusCode()).isEqualTo(400);
                    assertThat(dex.getMessage()).contains("client error");
                });
    }

    @Test
    void dispatch_serverError5xx_throwsRetryableException() throws Exception {
        // Arrange
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-3")
                .vendorId("ad-system")
                .httpMethod("PUT")
                .targetUrl(mockWebServer.url("/api/callback").toString())
                .body("hello")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> dispatcher.dispatch(message))
                .isInstanceOf(DispatchException.class)
                .satisfies(ex -> {
                    DispatchException dex = (DispatchException) ex;
                    assertThat(dex.isRetryable()).isTrue();
                    assertThat(dex.getStatusCode()).isEqualTo(503);
                    assertThat(dex.getMessage()).contains("server error");
                });
    }

    @Test
    void dispatch_connectionFailure_throwsRetryableException() {
        // Arrange: shutdown the web server to simulate connection refusal
        try {
            mockWebServer.shutdown();
        } catch (IOException e) {
            // ignore
        }

        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-4")
                .vendorId("ad-system")
                .httpMethod("POST")
                // Use a port that is guaranteed to fail
                .targetUrl("http://localhost:59999/failed")
                .body("hello")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> dispatcher.dispatch(message))
                .isInstanceOf(DispatchException.class)
                .satisfies(ex -> {
                    DispatchException dex = (DispatchException) ex;
                    assertThat(dex.isRetryable()).isTrue();
                    assertThat(dex.getStatusCode()).isEqualTo(0);
                    assertThat(dex.getMessage()).contains("Connection/timeout error");
                });
    }
}

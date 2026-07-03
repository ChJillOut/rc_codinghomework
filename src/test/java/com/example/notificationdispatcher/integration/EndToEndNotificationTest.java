package com.example.notificationdispatcher.integration;

import com.example.notificationdispatcher.TestcontainersConfiguration;
import com.example.notificationdispatcher.config.VendorProperties;
import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "notification.retry.delays[0]=100",
    "notification.retry.delays[1]=200",
    "notification.retry.delays[2]=300"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EndToEndNotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VendorProperties vendorProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Point the ad-system vendor to our mock server
        String mockUrl = mockWebServer.url("/api/callback").toString();
        vendorProperties.getVendors().get("ad-system").setUrl(mockUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void testSuccessfulNotificationDelivery() throws Exception {
        // Arrange: MockWebServer returns 200
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "vendorId", "ad-system",
                "body", "{\"event\":\"user_registered\",\"userId\":\"123\"}"
        ));

        // Act: Submit notification via REST API
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").exists())
                .andExpect(jsonPath("$.status").value("accepted"));

        // Assert: MockWebServer receives the HTTP call
        RecordedRequest recordedRequest = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getBody().readUtf8())
                .contains("user_registered");
    }

    @Test
    void testNotificationNotRetriedOn4xx() throws Exception {
        // Arrange: MockWebServer returns 400 (permanent failure)
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "vendorId", "ad-system",
                "body", "{\"event\":\"bad_event\"}"
        ));

        // Act: Submit notification
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        // Assert: Only ONE request received (no retry for 4xx)
        RecordedRequest firstRequest = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        assertThat(firstRequest).isNotNull();

        // Wait briefly to confirm no retry happens
        RecordedRequest secondRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(secondRequest).isNull();
    }

    @Test
    void testNotificationRetryOnTransientFailure() throws Exception {
        // Arrange: MockWebServer returns 503 Service Unavailable, then 200 OK
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "vendorId", "ad-system",
                "body", "{\"event\":\"retry_event\"}"
        ));

        // Act: Submit notification
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        // Assert: 1st request received (returns 503)
        RecordedRequest firstRequest = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        assertThat(firstRequest).isNotNull();

        // Assert: 2nd request received (retry, returns 200)
        RecordedRequest secondRequest = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
        assertThat(secondRequest).isNotNull();
        assertThat(secondRequest.getBody().readUtf8()).contains("retry_event");
    }

    @Test
    void testNotificationSentToDlqOnExhaustion() throws Exception {
        // Arrange: MockWebServer returns 503 for all 4 attempts (1 initial + 3 retries)
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        // Clear any leftover messages in DLQ
        while (rabbitTemplate.receiveAndConvert("notification.dlq") != null) {
            // drain
        }

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "vendorId", "ad-system",
                "body", "{\"event\":\"exhaustion_event\"}"
        ));

        // Act: Submit notification
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        // Assert: All 4 attempts received by MockWebServer
        for (int i = 0; i < 4; i++) {
            RecordedRequest req = mockWebServer.takeRequest(10, TimeUnit.SECONDS);
            assertThat(req).isNotNull();
        }

        // Wait a short time for the message to be routed to DLQ after the 4th failure
        com.example.notificationdispatcher.model.NotificationMessage dlqMessage = null;
        for (int i = 0; i < 30; i++) {
            dlqMessage = (com.example.notificationdispatcher.model.NotificationMessage) 
                    rabbitTemplate.receiveAndConvert("notification.dlq");
            if (dlqMessage != null) {
                break;
            }
            Thread.sleep(100);
        }

        // Verify the message in the DLQ is not null and has correct properties
        assertThat(dlqMessage).isNotNull();
        assertThat(dlqMessage.getVendorId()).isEqualTo("ad-system");
        assertThat(dlqMessage.getBody()).contains("exhaustion_event");
        assertThat(dlqMessage.getRetryCount()).isEqualTo(3);
    }
}

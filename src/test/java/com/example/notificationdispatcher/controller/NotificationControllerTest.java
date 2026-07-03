package com.example.notificationdispatcher.controller;

import com.example.notificationdispatcher.config.VendorProperties;
import com.example.notificationdispatcher.config.VendorProperties.VendorConfig;
import com.example.notificationdispatcher.model.NotificationMessage;
import com.example.notificationdispatcher.model.NotificationRequest;
import com.example.notificationdispatcher.service.NotificationProducer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationProducer producer;

    @MockitoBean
    private VendorProperties vendorProperties;

    @BeforeEach
    void setUp() {
        VendorConfig adSystemConfig = new VendorConfig();
        adSystemConfig.setUrl("https://ad-system.example.com/callback");
        adSystemConfig.setDefaultHeaders(Map.of(
                "Authorization", "Bearer ad-token",
                "Content-Type", "application/json"
        ));
        adSystemConfig.setTimeoutSeconds(10);

        when(vendorProperties.getVendors()).thenReturn(Map.of("ad-system", adSystemConfig));
    }

    @Test
    void submitNotification_validRequest_returns202() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .vendorId("ad-system")
                .body("{\"event\":\"test\"}")
                .build();

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(producer).send(any(NotificationMessage.class));
    }

    @Test
    void submitNotification_missingVendorId_returns400() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .body("{\"event\":\"test\"}")
                .build();

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));

        verify(producer, never()).send(any(NotificationMessage.class));
    }

    @Test
    void submitNotification_missingBody_returns400() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .vendorId("ad-system")
                .build();

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));

        verify(producer, never()).send(any(NotificationMessage.class));
    }

    @Test
    void submitNotification_unknownVendor_returns400() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .vendorId("unknown-vendor")
                .body("{\"event\":\"test\"}")
                .build();

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown vendor: unknown-vendor"));

        verify(producer, never()).send(any(NotificationMessage.class));
    }

    @Test
    void submitNotification_customHeaders_mergedWithDefaults() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .vendorId("ad-system")
                .body("{\"event\":\"test\"}")
                .headers(Map.of(
                        "X-Custom-Header", "custom-value",
                        "Content-Type", "text/plain"
                ))
                .build();

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("accepted"));

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(producer).send(captor.capture());

        NotificationMessage captured = captor.getValue();
        Map<String, String> mergedHeaders = captured.getHeaders();

        // Vendor default header preserved
        assertThat(mergedHeaders).containsEntry("Authorization", "Bearer ad-token");
        // Custom header added
        assertThat(mergedHeaders).containsEntry("X-Custom-Header", "custom-value");
        // Request override takes precedence over vendor default
        assertThat(mergedHeaders).containsEntry("Content-Type", "text/plain");
        assertThat(mergedHeaders).hasSize(3);
    }

    @Test
    void submitNotification_unexpectedError_returns500() throws Exception {
        NotificationRequest request = NotificationRequest.builder()
                .vendorId("ad-system")
                .body("{\"event\":\"test\"}")
                .build();

        // Simulate an unexpected runtime exception in the controller/properties bean lookup
        when(vendorProperties.getVendors()).thenThrow(new RuntimeException("Simulated Database Failure"));

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));

        verify(producer, never()).send(any(NotificationMessage.class));
    }
}

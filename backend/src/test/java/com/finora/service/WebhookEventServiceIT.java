package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventServiceIT extends AbstractIntegrationTest {

    @Autowired private WebhookEventService webhookEventService;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    void firstClaimSucceedsSecondClaimOfSameEventIdIsRejected() {
        String eventId = "evt_" + UUID.randomUUID();

        boolean first = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));
        boolean second = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void markProcessedAndMarkFailedSetStatusAndTimestamp() {
        String eventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(eventId, "RAZORPAY", "subscription.charged", Map.of());

        webhookEventService.markProcessed(eventId);

        WebhookEvent processed = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();

        String failedEventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(failedEventId, "RAZORPAY", "subscription.halted", Map.of());
        webhookEventService.markFailed(failedEventId);

        WebhookEvent failed = webhookEventRepository.findById(failedEventId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WebhookEvent.STATUS_FAILED);
    }
}

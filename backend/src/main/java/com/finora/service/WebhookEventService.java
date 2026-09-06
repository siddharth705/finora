package com.finora.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7). {@link #claim} must run, and succeed or fail, BEFORE
 * any subscription state change — that ordering is what closes the race between two concurrent
 * deliveries of the same Razorpay event id.
 *
 * <p>Uses {@code WebhookEventRepository.insertIfAbsent} (a native {@code INSERT ... ON CONFLICT DO
 * NOTHING RETURNING}), not {@code save()} — {@code WebhookEvent.eventId} is a manually-assigned
 * natural key, and Hibernate's {@code save()} on an entity whose id is already non-null performs a
 * SELECT+UPDATE (a merge), never an INSERT, so a plain {@code saveAndFlush()} + catch
 * {@code DataIntegrityViolationException} never actually throws for a duplicate event id — it was
 * tried first and replaced after {@code firstClaimSucceedsSecondClaimOfSameEventIdIsRejected} failed
 * against a real Postgres instance (second claim returned {@code true}, not {@code false}).
 */
@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventService(WebhookEventRepository webhookEventRepository, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean claim(String eventId, String provider, String eventType, Map<String, Object> payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Webhook payload is not serializable to JSON.", e);
        }
        return webhookEventRepository.insertIfAbsent(eventId, provider, eventType, payloadJson).isPresent();
    }

    @Transactional
    public void markProcessed(String eventId) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(WebhookEvent.STATUS_PROCESSED);
            event.setProcessedAt(Instant.now());
        });
    }

    @Transactional
    public void markFailed(String eventId) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(WebhookEvent.STATUS_FAILED);
            event.setProcessedAt(Instant.now());
        });
    }
}

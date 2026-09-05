package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §5). One method per Razorpay event type this application
 * acts on -- Tasks 7-10 (this plan) and Plan 2's downgrade/upgrade reconciliation each add one
 * {@code case}. Named for what it does, not {@code *Service}: a single-purpose collaborator used
 * only by {@link com.finora.controller.RazorpayWebhookController}, per CODING_STANDARDS.md's naming
 * rule.
 */
@Component
public class RazorpayWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookDispatcher.class);

    public void dispatch(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            // Tasks 7-10 add cases here: "subscription.authenticated"/"subscription.activated",
            // "subscription.charged", "subscription.pending", "subscription.halted",
            // "subscription.cancelled".
            default -> log.info("Razorpay webhook event '{}' received but not handled in V1.", eventType);
        }
    }
}

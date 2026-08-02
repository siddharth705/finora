package com.finora.service;

import java.math.BigDecimal;

/**
 * Abstraction over "actually send an SMS" -- same PhoneVerificationProvider/EmailProvider-style
 * boundary, so business services never talk to 2Factor (or any future replacement) directly.
 *
 * Scoped narrowly today: the only real caller is TransactionService.create()'s real-time
 * transaction alert (see sendTransactionAlert) -- there is no budget-alert, EMI-reminder, or other
 * SMS-triggering feature built yet. Those can reuse this same provider once they exist; this
 * interface isn't widened speculatively ahead of that.
 */
public interface SmsProvider {
    boolean isConfigured();

    SmsResult send(SmsRequest request);

    SmsResult sendTransactionAlert(String toPhoneNumber, String description, BigDecimal amount, String type);
}

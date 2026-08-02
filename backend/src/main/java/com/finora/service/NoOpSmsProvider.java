package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/** Used whenever no 2Factor API key is configured -- logs instead of sending. Unlike
 *  NoOpEmailProvider/NoOpPhoneVerificationProvider, there's no "return the content directly to
 *  the caller" fallback here, since a transaction alert has no equivalent dev-convenience use --
 *  it's a best-effort notification, not something the response body needs to carry. */
public class NoOpSmsProvider implements SmsProvider, SilentProductionFallback {

    private static final Logger log = LoggerFactory.getLogger(NoOpSmsProvider.class);

    @Override
    public boolean isConfigured() { return false; }

    @Override
    public SmsResult send(SmsRequest request) {
        log.info("No SMS provider configured — would have sent \"{}\" to {}", request.message(), request.to());
        return SmsResult.failure(ProviderType.TWO_FACTOR, "No SMS provider configured");
    }

    @Override
    public SmsResult sendTransactionAlert(String toPhoneNumber, String description, BigDecimal amount, String type) {
        log.info("No SMS provider configured — would have sent a transaction alert to {} ({} {})", toPhoneNumber, type, amount);
        return SmsResult.failure(ProviderType.TWO_FACTOR, "No SMS provider configured");
    }

    @Override
    public String requiredConfigHint() { return "TWO_FACTOR_API_KEY"; }
}

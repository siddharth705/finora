package com.finora.service;

import com.finora.util.PhoneMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/** Used whenever no 2Factor API key is configured -- logs instead of sending. Unlike
 *  NoOpEmailProvider/NoOpPhoneVerificationProvider, there's no "return the content directly to
 *  the caller" fallback here, since a transaction alert has no equivalent dev-convenience use --
 *  it's a best-effort notification, not something the response body needs to carry.
 *
 *  <p>These lines are written under PRODUCTION log levels, which is what separates this class from
 *  NoOpEmailProvider next door. A missing RESEND_API_KEY is a hard startup failure in the prod
 *  profile (ProductionConfigValidator), so NoOpEmailProvider provably cannot be the active bean
 *  there and is free to print an address a developer needs to read. A missing TWO_FACTOR_API_KEY
 *  is only a startup WARNING -- deliberately, since a transaction alert is a degraded notification
 *  and not a security gap -- so THIS class is reachable in production, and every alert it handles
 *  used to write a real customer's phone number, the alert body, and the transaction amount into
 *  the application log at INFO. TwoFactorSmsProvider, the bean this one stands in for, has masked
 *  its recipients since it was written; the fallback path simply never adopted the convention. */
public class NoOpSmsProvider implements SmsProvider, SilentProductionFallback {

    private static final Logger log = LoggerFactory.getLogger(NoOpSmsProvider.class);

    @Override
    public boolean isConfigured() { return false; }

    /** The message body is dropped rather than masked. Every body this provider is handed is
     *  composed from customer data -- see TwoFactorSmsProvider.sendTransactionAlert, which formats
     *  the amount and the merchant description straight into it -- so there is no partial form of
     *  it worth keeping. What an operator needs from this line is that a send was attempted and
     *  went nowhere, which the recipient and the reason already carry. */
    @Override
    public SmsResult send(SmsRequest request) {
        log.info("No SMS provider configured -- would have sent an SMS to {}", PhoneMasking.mask(request.to()));
        return SmsResult.failure(ProviderType.TWO_FACTOR, "No SMS provider configured");
    }

    /** {@code type} stays: it is a fixed enum-like token (EXPENSE/INCOME), not customer data, and
     *  it is what makes a run of these lines tell you which alert path is silently no-oping. The
     *  amount and the description go, for the reason above. */
    @Override
    public SmsResult sendTransactionAlert(String toPhoneNumber, String description, BigDecimal amount, String type) {
        log.info("No SMS provider configured -- would have sent a {} transaction alert to {}",
                type, PhoneMasking.mask(toPhoneNumber));
        return SmsResult.failure(ProviderType.TWO_FACTOR, "No SMS provider configured");
    }

    @Override
    public String requiredConfigHint() { return "TWO_FACTOR_API_KEY"; }
}

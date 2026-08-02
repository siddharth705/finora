package com.finora.service;

import com.finora.config.SmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Sends real SMS via 2Factor's transactional SMS API (https://2factor.in) -- chosen as an Indian
 * SMS gateway that supports transactional (non-OTP) sends, unlike Firebase Phone Authentication
 * which is scoped to auth OTPs only (see PhoneVerificationProvider's own doc comment for why
 * those two concerns stay separate: identity verification is Firebase's job, transactional
 * notifications are this provider's).
 *
 * NOTE: this app has no network access to verify 2Factor's exact current API contract against
 * their live docs at implementation time -- the endpoint/body shape below follows their
 * documented "Send/TSMS" transactional-SMS addon (POST, JSON body of From/To/Msg, API key in the
 * URL path). Treat this the same as this codebase's other network-unverifiable integration
 * details (e.g. pdfbox's pinned version) -- verify against a real 2Factor account before its
 * first production send.
 *
 * A send failure here is logged and reflected in SmsResult.success(), never thrown -- exactly the
 * same reasoning as ResendEmailProvider: a transaction alert failing to send must never fail (or
 * even slow down) the transaction-creation request it's a side effect of.
 */
public class TwoFactorSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorSmsProvider.class);
    private static final String SENDER_ID = "FINORA";

    private final SmsProperties smsProperties;
    private final RestClient restClient;

    public TwoFactorSmsProvider(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
        this.restClient = RestClient.create();
    }

    @Override
    public boolean isConfigured() { return true; }

    /** 2Factor's own response shape -- {"Status": "Success", "Details": "<message id>"} on
     *  success. Only the fields this app actually reads are modeled. */
    private record TwoFactorResponse(String Status, String Details) {}

    @Override
    public SmsResult send(SmsRequest request) {
        String url = "https://2factor.in/API/V1/%s/ADDON_SERVICES/SEND/TSMS".formatted(smsProperties.getTwoFactorApiKey());
        try {
            TwoFactorResponse response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("From", SENDER_ID, "To", request.to(), "Msg", request.message()))
                    .retrieve()
                    .body(TwoFactorResponse.class);

            boolean success = response != null && "Success".equalsIgnoreCase(response.Status());
            String messageId = response != null ? response.Details() : null;
            if (success) {
                log.info("SMS sent via 2Factor to {} (messageId={})", request.to(), messageId);
                return SmsResult.success(ProviderType.TWO_FACTOR, messageId);
            }
            log.error("2Factor reported failure sending SMS to {}: {}", request.to(), response);
            return SmsResult.failure(ProviderType.TWO_FACTOR, "2Factor reported a non-success status");
        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", request.to(), e.getMessage());
            return SmsResult.failure(ProviderType.TWO_FACTOR, e.getMessage());
        }
    }

    @Override
    public SmsResult sendTransactionAlert(String toPhoneNumber, String description, BigDecimal amount, String type) {
        String verb = "EXPENSE".equalsIgnoreCase(type) ? "debited" : "credited";
        String message = "Finora Alert: Rs.%s %s for %s.".formatted(amount, verb, description);
        return send(new SmsRequest(toPhoneNumber, message));
    }
}

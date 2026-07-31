package com.finora.service;

import com.finora.config.SmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Sends real SMS via Twilio's REST API. Twilio specifically (not a generic choice) because its
 * API is simple (one form-encoded POST, Basic Auth, no SDK needed) — same reasoning as choosing
 * Resend for email. Uses Spring's RestClient, already available via spring-web.
 *
 * Like ResendEmailService, a send failure here is logged, not rethrown — a user whose OTP was
 * generated and stored server-side successfully shouldn't see a hard error just because the SMS
 * provider had a transient hiccup; they can request a resend.
 */
public class TwilioSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsService.class);

    private final SmsProperties smsProperties;
    private final RestClient restClient;

    public TwilioSmsService(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
        this.restClient = RestClient.create();
    }

    @Override
    public boolean isConfigured() { return true; }

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + smsProperties.getAccountSid() + "/Messages.json";
        String credentials = smsProperties.getAccountSid() + ":" + smsProperties.getAuthToken();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("To", phoneNumber);
        body.add("From", smsProperties.getFromNumber());
        body.add("Body", "Your Finora verification code is " + otp + ". It expires in 10 minutes.");

        try {
            restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send OTP SMS to {}: {}", phoneNumber, e.getMessage());
        }
    }
}

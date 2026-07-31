package com.finora.config;

import com.finora.service.NoOpSmsService;
import com.finora.service.SmsService;
import com.finora.service.TwilioSmsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsConfig {

    @Bean
    public SmsService smsService(SmsProperties smsProperties) {
        boolean configured = smsProperties.getAccountSid() != null && !smsProperties.getAccountSid().isBlank()
                && smsProperties.getAuthToken() != null && !smsProperties.getAuthToken().isBlank();
        if (configured) {
            return new TwilioSmsService(smsProperties);
        }
        // No TWILIO_* credentials set — falls back to logging the OTP server-side instead of
        // sending it. Fine for local development; never acceptable in a real deployment, since
        // that would mean nobody outside server logs ever actually receives their code.
        return new NoOpSmsService();
    }
}

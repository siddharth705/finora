package com.finora.config;

import com.finora.service.EmailService;
import com.finora.service.NoOpEmailService;
import com.finora.service.ResendEmailService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailConfig {

    @Bean
    public EmailService emailService(EmailProperties emailProperties) {
        if (emailProperties.getApiKey() != null && !emailProperties.getApiKey().isBlank()) {
            return new ResendEmailService(emailProperties);
        }
        // No RESEND_API_KEY set — falls back to logging + returning the reset link directly
        // in the API response, exactly the previous dev-environment behavior.
        return new NoOpEmailService();
    }
}

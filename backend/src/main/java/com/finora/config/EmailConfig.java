package com.finora.config;

import com.finora.service.EmailProvider;
import com.finora.service.NoOpEmailProvider;
import com.finora.service.ResendEmailProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailConfig {

    @Bean
    public EmailProvider emailProvider(EmailProperties emailProperties) {
        if (emailProperties.getApiKey() != null && !emailProperties.getApiKey().isBlank()) {
            return new ResendEmailProvider(emailProperties);
        }
        // No RESEND_API_KEY set — falls back to logging + returning the reset link directly
        // in the API response, exactly the previous dev-environment behavior.
        return new NoOpEmailProvider();
    }
}

package com.finora.config;

import com.finora.service.NoOpSmsProvider;
import com.finora.service.SmsProvider;
import com.finora.service.TwoFactorSmsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsConfig {

    @Bean
    public SmsProvider smsProvider(SmsProperties smsProperties) {
        if (smsProperties.getTwoFactorApiKey() != null && !smsProperties.getTwoFactorApiKey().isBlank()) {
            return new TwoFactorSmsProvider(smsProperties);
        }
        // No TWO_FACTOR_API_KEY set — transaction alerts silently no-op (logged only).
        return new NoOpSmsProvider();
    }
}

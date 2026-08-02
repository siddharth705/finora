package com.finora.config;

import com.finora.service.NoOpSmsProvider;
import com.finora.service.SmsProvider;
import com.finora.service.TwoFactorSmsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsConfigTest {

    private final SmsConfig config = new SmsConfig();

    private SmsProperties propsWith(String apiKey) {
        SmsProperties props = new SmsProperties();
        props.setApiKey(apiKey);
        return props;
    }

    @Test
    void smsProvider_withARealApiKey_selectsTwoFactor() {
        SmsProvider provider = config.smsProvider(propsWith("real-2factor-key"));
        assertThat(provider).isInstanceOf(TwoFactorSmsProvider.class);
    }

    @Test
    void smsProvider_withNoApiKey_fallsBackToNoOp() {
        SmsProvider provider = config.smsProvider(propsWith(null));
        assertThat(provider).isInstanceOf(NoOpSmsProvider.class);
    }

    @Test
    void smsProvider_withABlankApiKey_fallsBackToNoOp() {
        SmsProvider provider = config.smsProvider(propsWith("   "));
        assertThat(provider).isInstanceOf(NoOpSmsProvider.class);
    }
}

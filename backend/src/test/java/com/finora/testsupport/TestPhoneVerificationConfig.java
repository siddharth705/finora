package com.finora.testsupport;

import com.finora.service.PhoneVerificationProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * @Import this into any IT test class that needs to exercise a phone-verification-gated flow --
 * without it, PhoneVerificationProvider resolves to the real FirebasePhoneVerificationProvider,
 * which is unconfigured in the test environment (no GOOGLE_APPLICATION_CREDENTIALS) and always
 * throws 503. @Primary so this fake wins over that real bean in the same application context.
 */
@TestConfiguration
public class TestPhoneVerificationConfig {

    @Bean
    @Primary
    public PhoneVerificationProvider phoneVerificationProvider() {
        return new FakePhoneVerificationProvider();
    }
}

package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used whenever no SMS provider is configured — logs instead of sending. This is the default,
 *  since sending a real SMS costs real money per message and needs a real Twilio (or similar)
 *  account this environment can't provision. */
public class NoOpSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(NoOpSmsService.class);

    @Override
    public boolean isConfigured() { return false; }

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        log.info("No SMS provider configured — would have sent OTP {} to {}", otp, phoneNumber);
    }
}

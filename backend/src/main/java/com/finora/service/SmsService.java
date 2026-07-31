package com.finora.service;

/**
 * Abstraction over "actually send an SMS" — same pattern as EmailService, and for the same
 * reason: this environment has no real provider credentials, so isConfigured() lets callers
 * know whether a real SMS will actually go out or whether the OTP only reaches server logs.
 */
public interface SmsService {
    boolean isConfigured();
    void sendOtp(String phoneNumber, String otp);
}

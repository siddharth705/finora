package com.finora.service;

/**
 * Identifies which real provider is behind a PhoneVerificationProvider/EmailProvider/SmsProvider
 * call -- a shared, typed alternative to scattering the same string literals ("FIREBASE" etc.)
 * across every entity/service that records which provider handled something.
 */
public enum ProviderType {
    FIREBASE,
    RESEND,
    TWO_FACTOR
}

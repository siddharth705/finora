package com.finora.service;

/**
 * Marks a service implementation that silently substitutes for a real integration when its
 * configuration is missing -- {@link NoOpEmailService} and {@link NoOpSmsService} are the two
 * that exist today, chosen automatically by {@code EmailConfig}/{@code SmsConfig} instead of
 * {@code ResendEmailService}/actual Twilio wiring whenever the relevant API key/credentials are
 * blank.
 *
 * <p>Reusable diagnostic, not a point patch: {@code ProductionConfigValidator} originally checked
 * only {@code JWT_SECRET}/{@code DB_PASSWORD} -- nothing failed startup when {@code
 * RESEND_API_KEY} was missing, so a production deployment that simply omitted it silently started
 * returning password-reset links directly in API responses instead of emailing them (a full
 * account-takeover primitive), and a missing Twilio config silently left OTPs only logged
 * server-side. Both gaps have since been fixed, but fixing them does not stop a *third* silent
 * fallback from being added next year with the same gap. This interface, plus
 * {@code SilentFallbackConfigValidationTest}, is what actually prevents that: any class named
 * {@code NoOp*} in this package is required to implement it (declaring the config it silently
 * substitutes for), and that hint is required to actually appear in
 * {@code ProductionConfigValidator}'s source. A new no-op fallback added without wiring up a
 * corresponding startup check fails the build instead of shipping quietly.
 */
public interface SilentProductionFallback {

    /**
     * The environment variable (or human-readable config name) whose absence causes this no-op
     * implementation to be selected instead of a real one. Must appear in
     * {@code ProductionConfigValidator}'s source -- see {@code SilentFallbackConfigValidationTest}.
     */
    String requiredConfigHint();
}

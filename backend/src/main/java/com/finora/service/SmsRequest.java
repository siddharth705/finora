package com.finora.service;

/** Provider-agnostic SMS content -- SmsProvider implementations translate this into whatever
 *  shape the real API (2Factor, or a future replacement) actually expects. */
public record SmsRequest(String to, String message) {}

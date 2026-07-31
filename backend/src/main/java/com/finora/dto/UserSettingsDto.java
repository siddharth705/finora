package com.finora.dto;

import java.math.BigDecimal;

public record UserSettingsDto(String email, String fullName, BigDecimal lowBalanceThreshold, String theme, String timezone) {
    public record UpdateRequest(BigDecimal lowBalanceThreshold, String theme, String timezone) {}
}

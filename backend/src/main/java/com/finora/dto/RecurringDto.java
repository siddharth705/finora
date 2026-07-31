package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringDto(
        String merchant,
        String label,       // Weekly | Biweekly | Monthly | Quarterly
        BigDecimal averageAmount,
        int occurrences,
        LocalDate lastDate,
        LocalDate nextEstimate
) {}

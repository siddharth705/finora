package com.finora.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReportDto(
        String month,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryAmount> categories
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
}

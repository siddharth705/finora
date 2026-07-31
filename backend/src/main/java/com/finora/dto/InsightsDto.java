package com.finora.dto;

import java.math.BigDecimal;
import java.util.List;

public record InsightsDto(
        List<String> sentences,
        List<CategoryMover> movers
) {
    public record CategoryMover(String category, BigDecimal current, BigDecimal priorAverage, Double pctChange) {}
}

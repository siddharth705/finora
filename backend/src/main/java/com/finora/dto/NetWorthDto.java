package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NetWorthDto(
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netWorth,
        List<SnapshotPoint> history
) {
    public record SnapshotPoint(LocalDate date, BigDecimal netWorth) {}
}

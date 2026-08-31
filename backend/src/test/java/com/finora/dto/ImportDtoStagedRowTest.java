package com.finora.dto;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.RowKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ImportDtoStagedRowTest {

    @Test
    void canonicalConstructor_carriesMerchantAndMerchantConfidence() {
        StagedRow row = new StagedRow(
                LocalDate.of(2026, 1, 1), "UPI-SWIGGY-12345", BigDecimal.TEN, "EXPENSE",
                "Food", "learned", null, false, null, null, null, RowKind.TRANSACTION,
                null, "SWIGGY", 1.0);

        assertThat(row.merchant()).isEqualTo("SWIGGY");
        assertThat(row.merchantConfidence()).isEqualTo(1.0);
    }

    @Test
    void preExistingConstructor_defaultsMerchantFieldsToNull() {
        StagedRow row = new StagedRow(
                LocalDate.of(2026, 1, 1), "UPI-SWIGGY-12345", BigDecimal.TEN, "EXPENSE",
                "Food", "learned", null, false, null, null);

        assertThat(row.merchant()).isNull();
        assertThat(row.merchantConfidence()).isNull();
    }
}

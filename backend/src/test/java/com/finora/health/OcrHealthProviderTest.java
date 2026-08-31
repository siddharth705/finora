package com.finora.health;

import com.finora.imports.pdf.ocr.TesseractEngine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcrHealthProviderTest {

    /** TesseractEngine.available() shells out to the real PATH -- same constraint
     *  TesseractRecogniser itself lives with (no test mocks the binary's presence). This asserts
     *  the provider's status is DERIVED from that real check, not hardcoded to either branch. */
    @Test
    void check_reflectsWhetherTheBinaryIsOnPath() {
        OcrHealthProvider provider = new OcrHealthProvider();

        HealthCheckResult result = provider.check();

        HealthStatus expected = TesseractEngine.available() ? HealthStatus.UP : HealthStatus.DEGRADED;
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.detail()).isNotBlank();
    }
}

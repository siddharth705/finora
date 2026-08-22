package com.finora.health;

import com.finora.imports.pdf.ocr.TesseractEngine;
import org.springframework.stereotype.Component;

/**
 * Mirrors TesseractRecogniser.supports(): honestly reports the {@code tesseract} binary's absence
 * rather than assuming it (see that class's own doc comment). DEGRADED, not DOWN, when
 * unavailable -- a scanned/image-only statement degrades to {@code IMPORT_SCANNED_OCR_REQUIRED},
 * the same behaviour as before OCR was ever installed; native-text PDFs are unaffected.
 */
@Component
public class OcrHealthProvider implements HealthProvider {

    @Override
    public String name() {
        return "OCR (Tesseract)";
    }

    @Override
    public String category() {
        return "Integrations";
    }

    @Override
    public HealthCheckResult check() {
        if (TesseractEngine.available()) {
            return HealthCheckResult.up("tesseract binary found on PATH -- scanned/image-only statements can be read");
        }
        return HealthCheckResult.degraded("tesseract binary not found on PATH -- scanned/image-only statements "
                + "fall back to requiring the user re-upload a text-based file");
    }
}

package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
 * DocumentContext is the shared recorder threaded through the PDF/CSV pipelines -- these tests
 * cover its own contract directly (metadata/fingerprint building, capability dedup) rather than
 * via a full parse run, which the per-capability PdfPreviewGenerator tests already exercise.
 */
class DocumentContextTest {

    @Test
    void buildMetadata_reportsStructuralFactsAsRecorded() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
        ctx.recordPages(2);
        ctx.recordTables(1);
        ctx.recordHeaders(List.of("Date", "Description", "Amount", "Balance"));

        var metadata = ctx.buildMetadata();

        assertThat(metadata.sourceFormat()).isEqualTo("PDF");
        assertThat(metadata.parser()).isEqualTo("PdfPreviewGenerator");
        assertThat(metadata.pages()).isEqualTo(2);
        assertThat(metadata.tables()).isEqualTo(1);
        assertThat(metadata.columns()).isEqualTo(4);
        assertThat(metadata.headers()).containsExactly("Date", "Description", "Amount", "Balance");
    }

    @Test
    void buildMetadata_flagsHeadersNoCapabilityRecognizes_asUnknown() {
        DocumentContext ctx = new DocumentContext("CSV", "PreviewGenerator");
        ctx.recordHeaders(List.of("Date", "Amount", "Loyalty Points"));

        var metadata = ctx.buildMetadata();

        assertThat(metadata.unknownHeaders()).containsExactly("Loyalty Points");
    }

    @Test
    void recordHeaders_deduplicatesRepeatedHeaderNames() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
        ctx.recordHeaders(List.of("Date", "Amount"));
        ctx.recordHeaders(List.of("Date", "Amount")); // e.g. a repeated header on a later page

        assertThat(ctx.buildMetadata().headers()).containsExactly("Date", "Amount");
    }

    @Test
    void record_firesOnlyOnceForTheSameCapability_regardlessOfHowManyRowsTriggerIt() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
        ctx.record("RUNNING_BALANCE");
        ctx.record("RUNNING_BALANCE");
        ctx.record("RUNNING_BALANCE");

        assertThat(ctx.capabilities()).hasSize(1);
        assertThat(ctx.capabilities().get(0).capability()).isEqualTo("RUNNING_BALANCE");
        assertThat(ctx.capabilities().get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void buildFingerprint_isDeterministic_sameStructureAlwaysProducesTheSameId() {
        DocumentContext first = new DocumentContext("PDF", "PdfPreviewGenerator");
        first.recordHeaders(List.of("Date", "Description", "Amount", "Balance"));

        DocumentContext second = new DocumentContext("PDF", "PdfPreviewGenerator");
        second.recordHeaders(List.of("Date", "Description", "Amount", "Balance"));

        assertThat(first.buildFingerprint()).isEqualTo(second.buildFingerprint());
        assertThat(first.buildFingerprint()).startsWith("FP-");
    }

    @Test
    void buildFingerprint_isInsensitiveToHeaderExtractionOrder() {
        DocumentContext first = new DocumentContext("PDF", "PdfPreviewGenerator");
        first.recordHeaders(List.of("Date", "Description", "Amount"));

        DocumentContext second = new DocumentContext("PDF", "PdfPreviewGenerator");
        second.recordHeaders(List.of("Amount", "Date", "Description"));

        assertThat(first.buildFingerprint()).isEqualTo(second.buildFingerprint());
    }

    @Test
    void buildFingerprint_differsForAGenuinelyDifferentHeaderSet() {
        DocumentContext first = new DocumentContext("PDF", "PdfPreviewGenerator");
        first.recordHeaders(List.of("Date", "Description", "Amount"));

        DocumentContext second = new DocumentContext("PDF", "PdfPreviewGenerator");
        second.recordHeaders(List.of("Date", "Description", "Amount", "Reference Number"));

        assertThat(first.buildFingerprint()).isNotEqualTo(second.buildFingerprint());
    }

    @Test
    void buildFingerprint_differsForADifferentSourceFormat_evenWithIdenticalHeaders() {
        DocumentContext pdf = new DocumentContext("PDF", "PdfPreviewGenerator");
        pdf.recordHeaders(List.of("Date", "Amount"));

        DocumentContext csv = new DocumentContext("CSV", "PreviewGenerator");
        csv.recordHeaders(List.of("Date", "Amount"));

        assertThat(pdf.buildFingerprint()).isNotEqualTo(csv.buildFingerprint());
    }
}

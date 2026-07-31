/**
 * PDF statement import -- Milestone 1. Deliberately isolated: nothing outside this package
 * (com.finora.imports, ImportService, ImportSession, the review/confirm pipeline) knows any of
 * these classes exist. The one and only bridge point is {@link com.finora.imports.pdf.PdfPreviewGenerator},
 * which produces the exact same {@code com.finora.dto.ImportDto.StagingResponse} CSV's own
 * PreviewGenerator produces -- everything downstream of staging (ImportSession, confirm,
 * validation, concurrency) is reused completely unmodified.
 *
 * Scope, per the agreed Milestone 1 boundaries:
 *   - Digital (text-based) PDF bank statements only. No OCR, no scanned PDFs.
 *   - One real layout worked through end-to-end (see the golden test fixture,
 *     src/test/resources/pdf/sbi_sample_statement.pdf) rather than several banks half-supported.
 *   - No {@code FinancialDocument} canonical model, no {@code DocumentParser}/extractor
 *     interface, no Document Profiles/Extraction Templates, no AI. Each of those was explicitly
 *     deferred until a second real implementation (or a dedicated AI RFC) exists to justify them
 *     -- see the ADR-0002 follow-up discussion this milestone came out of.
 *   - Any intermediate PDF-specific representation ({@link com.finora.imports.pdf.PositionedText},
 *     the row-bucketing in {@link com.finora.imports.pdf.PdfTableLocator}) stays private to this
 *     package. The moment something outside it needs to reference these types directly, that's
 *     the signal they've stopped being an implementation detail -- at which point they should be
 *     named and designed as a real shared contract deliberately, not left as an accidental leak.
 *
 * The core technical problem this package exists to solve: naive PDF text extraction (a plain
 * "read all the text in drawing order" stripper) discards which column a piece of text belongs
 * to -- for a table with separate Debit and Credit columns, that's exactly the information that
 * distinguishes a debit row from a credit row. This was verified empirically before writing any
 * extraction code (not assumed): the same synthetic statement, extracted naively, produced
 * identically-shaped output for a debit row and a credit row -- "date, description, amount,
 * balance" either way, with the amount's column identity gone. Position-aware extraction
 * (tracking each text run's x/y coordinates and bucketing by nearest column) is what actually
 * recovers that information -- see PdfTextExtractor and PdfTableLocator's own doc comments.
 *
 * A second real discovery from actually building this (not planned in advance): once PDF
 * extraction produces the same {@code Map<String,String>} row shape CSV's own
 * {@code CsvParser.zipRow()} already produces, {@link com.finora.imports.TransactionNormalizer}
 * and {@link com.finora.imports.StatementValidator} turn out to already be reusable as-is --
 * they operate on that map shape generically, with no CSV-specific assumption baked in. That
 * reuse is used directly in PdfPreviewGenerator rather than re-implemented; it's exactly the kind
 * of commonality-discovered-from-a-real-second-implementation the whole "generalize after two
 * implementations exist" principle was waiting for -- and it's *narrower* than a full shared
 * parser interface would have been, which is why building the interface in advance would have
 * gotten this specific, useful reuse wrong.
 */
package com.finora.imports.pdf;

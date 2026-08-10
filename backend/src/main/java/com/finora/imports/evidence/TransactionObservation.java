package com.finora.imports.evidence;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One acquisition's reading of one transaction row, carrying every signal design §2.2's
 * correlation algorithm scores against -- {@code page} is the hard gate; {@code date}/{@code amount}
 * are the two strongest scored signals; {@code direction}/{@code description}/{@code boundingBox}/
 * {@code ordinalPosition} corroborate but never decide alone.
 *
 * <p>Deliberately not a {@link FieldFact}: a transaction row is a bundle of several
 * {@link MaterialField}s (date, amount, direction, description) observed together as one physical
 * row, not a single scalar value with one provenance chain. Forcing it through {@code FieldFact<T>}
 * would require inventing a "whole row" {@code MaterialField} that doesn't name any real field, or
 * smuggling cross-field data into {@code T} -- either way, weakening {@code FieldFact}'s existing,
 * working single-field contract to fit a concern it doesn't have (Phase A's evidence-status
 * derivation never needed geometry or row position). Kept as its own purpose-built type instead.
 *
 * @param page 0-indexed, matching {@code com.finora.imports.pdf.PositionedText#pageIndex}
 * @param date nullable -- a row whose date could not be parsed still needs to be correlatable
 *             (design's "missing dates" case degrades the correlation, never crashes it)
 * @param amount nullable, same reasoning as {@code date}
 * @param direction nullable; compared for equality only (e.g. "DEBIT"/"CREDIT"), no normalization
 *                  performed here -- callers are expected to have already normalized casing/vocabulary
 * @param description nullable
 * @param boundingBox nullable -- absent when the acquisition source didn't preserve geometry
 * @param ordinalPosition nullable; the row's index within its reconstructed section, 0-based
 */
public record TransactionObservation(
        int page,
        LocalDate date,
        BigDecimal amount,
        String direction,
        String description,
        BoundingBox boundingBox,
        Integer ordinalPosition) {

    public TransactionObservation {
        if (page < 0) {
            throw new IllegalArgumentException("page must be a non-negative 0-indexed page number");
        }
        if (ordinalPosition != null && ordinalPosition < 0) {
            throw new IllegalArgumentException("ordinalPosition must be non-negative when present");
        }
    }
}

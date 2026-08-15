package com.finora.integrations.google.merchant;

/**
 * What a {@link MerchantEmailParser} decided about one message — Phase C5.
 *
 * <p>Three outcomes, deliberately distinct rather than a boolean plus a nullable receipt, because
 * they mean different things once C5-B writes them back to {@code gmail_processed_messages.outcome}:
 *
 * <ul>
 *   <li>{@link Status#PARSED} — a receipt was extracted. Maps toward {@code Outcome.PARSED} once
 *       staged, not before: parsing is not the same fact as staging succeeding.</li>
 *   <li>{@link Status#NOT_A_RECEIPT} — the parser recognised the domain but this message is not the
 *       shape it extracts (a marketing email, a shipping-only notification with no amount). Maps to
 *       {@code Outcome.SKIPPED_NOT_RECEIPT}. Not a failure — most mail from a trusted merchant is
 *       exactly this.</li>
 *   <li>{@link Status#MALFORMED} — the parser recognised this AS a receipt but could not extract it
 *       reliably (an amount pattern that doesn't parse, a missing required field). Maps to {@code
 *       Outcome.PARSE_FAILED}. Distinct from {@code NOT_A_RECEIPT} because it is the signal that a
 *       merchant changed its template and the parser needs updating — collapsing the two would hide
 *       that behind ordinary marketing-mail noise.</li>
 * </ul>
 */
public record ParserResult(Status status, ParsedReceipt receipt, String reason) {

    public enum Status { PARSED, NOT_A_RECEIPT, MALFORMED }

    public ParserResult {
        if (status == Status.PARSED && receipt == null) {
            throw new IllegalArgumentException("PARSED requires a receipt");
        }
        if (status != Status.PARSED && receipt != null) {
            throw new IllegalArgumentException(status + " must not carry a receipt");
        }
    }

    public boolean isParsed() { return status == Status.PARSED; }

    public static ParserResult parsed(ParsedReceipt receipt) {
        return new ParserResult(Status.PARSED, receipt, null);
    }

    public static ParserResult notAReceipt(String reason) {
        return new ParserResult(Status.NOT_A_RECEIPT, null, reason);
    }

    public static ParserResult malformed(String reason) {
        return new ParserResult(Status.MALFORMED, null, reason);
    }
}

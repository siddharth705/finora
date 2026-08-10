package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import com.finora.imports.product.EvidenceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionSameFactGrouperTest {

    private static TransactionObservation row(int page, LocalDate date, String amount, String direction,
            String description, BoundingBox box, Integer ordinal) {
        return new TransactionObservation(page, date, amount == null ? null : new BigDecimal(amount),
                direction, description, box, ordinal);
    }

    private static TransactionFieldObservation<String> obs(TransactionObservation position, String value,
            ProvenanceNode.Acquisition acquisition) {
        return new TransactionFieldObservation<>(position,
                new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, value, List.of(acquisition)),
                EvidenceSource.ROW_DATA);
    }

    @Test
    void twoSameFactObservations_bothInGroup() {
        TransactionObservation nativePos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation ocrPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);

        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(List.of(
                obs(nativePos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                obs(ocrPos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.OCR))));

        assertThat(result.sameFactGroup()).hasSize(2);
        assertThat(result.excludedAsDifferent()).isEmpty();
        assertThat(result.excludedAsUncertain()).isEmpty();
    }

    @Test
    void differentFactObservation_excludedAsDifferent_notTreatedAsContradiction() {
        TransactionObservation anchorPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation unrelatedPos = row(1, LocalDate.of(2026, 3, 20), "750.00", "CREDIT",
                "NEFT/salary/acme corp", new BoundingBox(10, 300, 200, 12), 9);

        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(List.of(
                obs(anchorPos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                obs(unrelatedPos, "CREDIT", new ProvenanceNode.Acquisition(TextSource.OCR))));

        assertThat(result.sameFactGroup()).hasSize(1);
        assertThat(result.excludedAsDifferent()).hasSize(1);
        assertThat(result.excludedAsUncertain()).isEmpty();
    }

    @Test
    void uncertainCorrelation_excludedAsUncertain_neitherAgreeNorDisagree() {
        // Weak, partial signals that correlate UNCERTAIN, not DIFFERENT_FACT.
        TransactionObservation anchorPos = row(0, LocalDate.of(2026, 3, 5), "2500.00", null, null, null, null);
        TransactionObservation ambiguousPos = row(0, LocalDate.of(2026, 3, 6), "2500.00", null, null, null, null);

        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(List.of(
                obs(anchorPos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                obs(ambiguousPos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.OCR))));

        assertThat(result.sameFactGroup()).hasSize(1);
        assertThat(result.excludedAsUncertain()).hasSize(1);
        assertThat(result.excludedAsDifferent()).isEmpty();
    }

    @Test
    void singleObservation_isTheWholeGroup() {
        TransactionObservation pos = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT", null, null, null);

        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(
                List.of(obs(pos, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))));

        assertThat(result.sameFactGroup()).hasSize(1);
    }

    @Test
    void empty_isEmptyGroup() {
        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(List.of());

        assertThat(result.sameFactGroup()).isEmpty();
    }

    @Test
    void threeGenuinelySameFactObservations_isRejected_notSilentlyGrouped() {
        // Audit finding: the anchor-based algorithm is only valid for at most 2 genuinely
        // agreeing sources. Three observations that ALL correlate SAME_FACT with the anchor must
        // fail loudly rather than silently produce a non-transitive-verified grouping.
        TransactionObservation posA = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation posB = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);
        TransactionObservation posC = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(9, 99, 201, 12), 4);

        List<TransactionFieldObservation<String>> threeSources = List.of(
                obs(posA, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                obs(posB, "DEBIT", new ProvenanceNode.Acquisition(TextSource.OCR)),
                obs(posC, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PLUS_OCR)));

        assertThatThrownBy(() -> TransactionSameFactGrouper.group(threeSources))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void threeObservations_onlyTwoGenuinelySameFact_stillGroupsSuccessfully() {
        // The guard is on the GROUP size, not the input size -- an input of 3 where one is
        // DIFFERENT_FACT must still succeed (this is exactly what
        // TransactionEvidencePipelineTest's audit-finding regression test relies on).
        TransactionObservation posA = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(10, 100, 200, 12), 4);
        TransactionObservation posB = row(0, LocalDate.of(2026, 3, 5), "2500.00", "DEBIT",
                "UPI paytm priya sharma", new BoundingBox(11, 101, 199, 12), 4);
        TransactionObservation posC = row(0, LocalDate.of(2026, 3, 20), "750.00", "CREDIT",
                "NEFT/salary/acme corp", new BoundingBox(10, 400, 200, 12), 9);

        SameFactGroupingResult<String> result = TransactionSameFactGrouper.group(List.of(
                obs(posA, "DEBIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)),
                obs(posB, "DEBIT", new ProvenanceNode.Acquisition(TextSource.OCR)),
                obs(posC, "CREDIT", new ProvenanceNode.Acquisition(TextSource.NATIVE_PLUS_OCR))));

        assertThat(result.sameFactGroup()).hasSize(2);
        assertThat(result.excludedAsDifferent()).hasSize(1);
    }
}

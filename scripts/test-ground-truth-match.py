#!/usr/bin/env python3
"""Mechanism tests for ground-truth matching. Run: python3 scripts/test-ground-truth-match.py

Stdlib unittest, no dependencies, no corpus. The real corpus cannot reach CI, so these synthetic
fixtures are the only way this logic is tested -- the same split test-corpus-diff.py already uses.

Every fixture is invented. None is derived from a real statement.
"""

import importlib.util
import unittest
from pathlib import Path

_s = importlib.util.spec_from_file_location(
    "gt", Path(__file__).resolve().parent / "ground-truth-match.py")
gt = importlib.util.module_from_spec(_s)
_s.loader.exec_module(gt)


def section(index, rows, product="SAVINGS", masked=None, **statement_fields):
    s = {"index": index, "rows": rows, "detectedProduct": product,
         "suggestedAccountType": product, "accountNumberMasked": masked,
         "productConfidence": 0.9, "productNeedsReview": False, "verification": {}}
    s.update(statement_fields)
    return s


def record(sections):
    return {"schema": 1, "file": "synthetic.pdf", "status": "ok",
            "observed": {"sections": len(sections), "rows": sum(s["rows"] for s in sections),
                         "sectionDetail": sections},
            "derived": {"documentClassification": "PARSED_COMPLETE"}}


def entity(eid, product, presence="DETECTED", txns=None, legit=None, masked=None,
           **expected_statement_fields):
    e = {"id": eid, "expectedPresence": presence, "expectedProduct": product,
         "expectedTransactions": txns}
    if legit is not None:
        e["zeroTransactionsLegitimate"] = {"value": legit, "evidence": {"source": "DOCUMENT"}}
    if masked:
        e["expectedIdentity"] = {"accountNumberMasked": masked}
    e.update(expected_statement_fields)
    return e


# The observed record both §6 ground truths are judged against. Byte-identical in each case.
#
# The deposits carry DISTINCT masked numbers, and that is not a convenience -- it is a precondition the
# design note did not state. Shivani's real record has neither: both deposit sections report
# accountNumberMasked null and the same suggested type, so nothing distinguishes them and the matcher
# correctly answers AMBIGUOUS (see TheRealShivaniShapeIsAmbiguousToday). The pass/fail discrimination
# therefore needs attribute extraction to land FIRST. This fixture tests the mechanism; the test below
# records the dependency.
COMPOSITE = record([section(0, 75, "SAVINGS", "****0001"),
                    section(1, 0, "INVESTMENT", "****1111"),
                    section(2, 0, "INVESTMENT", "****2222")])


class TheDiscriminationTest(unittest.TestCase):
    """Design note §6. If these two do not diverge, nothing downstream can, and the model failed."""

    def test_legitimately_empty_deposits_pass(self):
        truth = {"entities": [
            entity("savings", "SAVINGS", txns=75, masked="****0001"),
            entity("rd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE", masked="****1111"),
            entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE", masked="****2222")]}

        r = gt.match(truth, COMPOSITE)

        self.assertEqual(gt.PASS, r["verdict"])
        self.assertTrue(all(e["outcome"] == gt.MATCHED for e in r["entities"]))

    def test_the_same_record_fails_when_the_document_says_the_deposits_have_history(self):
        """Identical observed output. Only the human's assertion differs -- and that is the point."""
        truth = {"entities": [
            entity("savings", "SAVINGS", txns=75, masked="****0001"),
            entity("rd-1", "INVESTMENT", txns=12, legit="FALSE", masked="****1111"),
            entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE", masked="****2222")]}

        r = gt.match(truth, COMPOSITE)

        self.assertEqual(gt.FAIL, r["verdict"])
        rd = next(e for e in r["entities"] if e["id"] == "rd-1")
        self.assertEqual(gt.FAIL, rd["status"])
        self.assertIn("under-extracted", rd["detail"])

    def test_the_two_verdicts_differ_on_one_unchanged_record(self):
        """States the property directly, so a refactor cannot make both outcomes identical."""
        a = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE")]}
        b = {"entities": [entity("fd-1", "INVESTMENT", txns=12, legit="FALSE")]}
        one = record([section(0, 0, "INVESTMENT")])

        self.assertNotEqual(gt.match(a, one)["verdict"], gt.match(b, one)["verdict"])


class TheRealShivaniShapeIsAmbiguousToday(unittest.TestCase):
    """A DEPENDENCY the design note did not state, found by writing these tests.

    Shivani's actual record reports both deposit sections with accountNumberMasked null and the same
    suggested type. Nothing distinguishes them, so the honest answer is AMBIGUOUS -- and the §6
    pass/fail discrimination cannot run against that record until RD/FD attribute extraction gives the
    sections something to be told apart by. Ground truth is still worth writing for it now: AMBIGUOUS
    is a real, reportable result, and it is a far better answer than today's silent success.
    """

    def test_shivanis_current_shape_yields_AMBIGUOUS_not_a_guess(self):
        observed = record([section(0, 75, "SAVINGS", "****0001"),
                           section(1, 0, "SAVINGS"), section(2, 0, "SAVINGS")])
        truth = {"entities": [
            entity("savings", "SAVINGS", txns=75, masked="****0001"),
            entity("rd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="UNKNOWN"),
            entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="UNKNOWN")]}

        r = gt.match(truth, observed)

        self.assertEqual(gt.FAIL, r["verdict"])          # both deposits expected, neither classified
        self.assertEqual({gt.MISSING}, {e["outcome"] for e in r["entities"]
                                        if e["id"] in ("rd-1", "fd-1")})
        # And it does NOT pass. Today the same document imports "successfully".


class MissingIsNotZero(unittest.TestCase):

    def test_an_expected_entity_that_is_not_detected_is_MISSING_and_fails(self):
        truth = {"entities": [entity("savings", "SAVINGS", txns=75, masked="****0001"),
                              entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE")]}

        r = gt.match(truth, record([section(0, 75, "SAVINGS", "****0001")]))

        self.assertEqual(gt.FAIL, r["verdict"])
        fd = next(e for e in r["entities"] if e["id"] == "fd-1")
        self.assertEqual(gt.MISSING, fd["outcome"])

    def test_MISSING_is_distinct_from_detected_with_zero_rows(self):
        """The distinction the whole model exists for: not detected != detected and empty."""
        truth = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE")]}

        absent = gt.match(truth, record([]))
        empty = gt.match(truth, record([section(0, 0, "INVESTMENT")]))

        self.assertEqual(gt.MISSING, absent["entities"][0]["outcome"])
        self.assertEqual(gt.MATCHED, empty["entities"][0]["outcome"])
        self.assertNotEqual(absent["verdict"], empty["verdict"])

    def test_an_entity_expected_absent_is_not_reported_missing(self):
        truth = {"entities": [entity("fd-1", "INVESTMENT", presence="ABSENT")]}

        self.assertEqual(gt.PASS, gt.match(truth, record([]))["verdict"])


class UnknownNeverBecomesTrue(unittest.TestCase):

    def test_zero_rows_with_UNKNOWN_legitimacy_is_review_never_pass(self):
        truth = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_YET_ESTABLISHED",
                                     legit="UNKNOWN")]}

        r = gt.match(truth, record([section(0, 0, "INVESTMENT")]))

        self.assertEqual(gt.REVIEW, r["verdict"])
        self.assertIn("UNKNOWN", r["entities"][0]["detail"])

    def test_a_missing_legitimacy_assertion_behaves_as_UNKNOWN_not_as_TRUE(self):
        """Absence of the field must not be silently permissive -- nobody has looked."""
        truth = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE")]}

        self.assertEqual(gt.REVIEW, gt.match(truth, record([section(0, 0, "INVESTMENT")]))["verdict"])

    def test_UNKNOWN_and_TRUE_do_not_produce_the_same_verdict(self):
        """Non-vacuity: if these ever agree, the review state has silently become a pass."""
        one = record([section(0, 0, "INVESTMENT")])
        unknown = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE",
                                       legit="UNKNOWN")]}
        true_ = {"entities": [entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE")]}

        self.assertNotEqual(gt.match(unknown, one)["verdict"], gt.match(true_, one)["verdict"])


class AmbiguityIsAnAnswer(unittest.TestCase):

    def test_two_indistinguishable_deposits_are_AMBIGUOUS_not_arbitrarily_paired(self):
        """No identity, same product type, both empty. Guessing is how money reaches the wrong
        product, so the honest answer is that we cannot tell."""
        truth = {"entities": [entity("rd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE"),
                              entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE")]}

        r = gt.match(truth, record([section(0, 0, "INVESTMENT"), section(1, 0, "INVESTMENT")]))

        self.assertEqual(gt.REVIEW, r["verdict"])
        self.assertEqual({gt.AMBIGUOUS}, {e["outcome"] for e in r["entities"]})

    def test_identity_resolves_what_product_type_alone_cannot(self):
        truth = {"entities": [
            entity("rd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE", masked="****1111"),
            entity("fd-1", "INVESTMENT", txns="NOT_APPLICABLE", legit="TRUE", masked="****2222")]}

        r = gt.match(truth, record([section(0, 0, "INVESTMENT", "****2222"),
                                    section(1, 0, "INVESTMENT", "****1111")]))

        self.assertEqual(gt.PASS, r["verdict"])
        # Paired across a positional swap: fd-1 is section 0. Position is not identity.
        self.assertEqual(0, next(e for e in r["entities"] if e["id"] == "fd-1")["section"])
        self.assertEqual(1, next(e for e in r["entities"] if e["id"] == "rd-1")["section"])


class UnexpectedSections(unittest.TestCase):

    def test_a_section_with_no_expected_entity_is_UNEXPECTED_and_needs_review(self):
        truth = {"entities": [entity("savings", "SAVINGS", txns=75, masked="****0001")]}

        r = gt.match(truth, record([section(0, 75, "SAVINGS", "****0001"),
                                    section(1, 0, "CREDIT_CARD")]))

        self.assertEqual(gt.REVIEW, r["verdict"])
        self.assertEqual(gt.UNEXPECTED, r["unexpected"][0]["outcome"])


class UnestablishedIsNotAgreement(unittest.TestCase):

    def test_NOT_YET_ESTABLISHED_transaction_count_does_not_pass_by_default(self):
        """Establishment is per field. An uncounted expectation must not read as agreement, or the
        parser's own output becomes the definition of correct."""
        truth = {"entities": [entity("savings", "SAVINGS", txns="NOT_YET_ESTABLISHED",
                                     masked="****0001")]}

        r = gt.match(truth, record([section(0, 75, "SAVINGS", "****0001")]))

        self.assertEqual(gt.PASS, r["verdict"])          # nothing contradicted
        self.assertEqual("as expected", r["entities"][0]["detail"])
        # ...but it must not be mistaken for a verified count. That is GroundTruthStatus's job,
        # deliberately not this matcher's -- see the design note section 4.3.

    def test_a_wrong_product_classification_fails_even_with_the_right_row_count(self):
        truth = {"entities": [entity("rd-1", "INVESTMENT", txns=0, legit="TRUE",
                                     masked="****1111")]}

        r = gt.match(truth, record([section(0, 0, "SAVINGS", "****1111")]))

        self.assertEqual(gt.FAIL, r["verdict"])
        self.assertIn("product", r["entities"][0]["detail"])


class StatementLevelFacts(unittest.TestCase):
    """Balance/period/credit-card-summary -- statement-level facts, not per-transaction ledger
    detail, so unlike VALUE_DIMENSIONS these apply to a REAL_CORPUS observation exactly like
    expectedProduct already does. Every field here is optional-to-assert: its absence from the
    ground truth must never read as agreement or as a failure."""

    # rows=1 throughout (not 0): a zero-row section triggers the separate zero-transactions-
    # legitimacy check below, which is orthogonal to what these tests exercise and would fail them
    # on an unrelated axis.

    def test_a_matched_opening_balance_passes(self):
        truth = {"entities": [entity("acct", "SAVINGS", txns="NOT_YET_ESTABLISHED",
                                     expectedOpeningBalance="1000.00")]}
        rec = record([section(0, 1, "SAVINGS", openingBalance="1000.00")])

        self.assertEqual(gt.PASS, gt.match(truth, rec)["verdict"])

    def test_a_mismatched_opening_balance_fails(self):
        truth = {"entities": [entity("acct", "SAVINGS", txns="NOT_YET_ESTABLISHED",
                                     expectedOpeningBalance="1000.00")]}
        rec = record([section(0, 1, "SAVINGS", openingBalance="999.00")])

        self.assertEqual(gt.FAIL, gt.match(truth, rec)["verdict"])

    def test_an_unasserted_field_never_affects_the_verdict(self):
        """No expectedOpeningBalance key at all -- must not be treated as an expected null."""
        truth = {"entities": [entity("acct", "SAVINGS", txns="NOT_YET_ESTABLISHED")]}
        rec = record([section(0, 1, "SAVINGS", openingBalance="1000.00")])

        self.assertEqual(gt.PASS, gt.match(truth, rec)["verdict"])

    def test_an_asserted_field_the_probe_never_observed_is_review_not_pass(self):
        truth = {"entities": [entity("acct", "SAVINGS", txns="NOT_YET_ESTABLISHED",
                                     expectedClosingBalance="1500.00")]}
        rec = record([section(0, 1, "SAVINGS", closingBalance=None)])

        self.assertEqual(gt.REVIEW, gt.match(truth, rec)["verdict"])

    def test_every_statement_field_is_independently_asserted(self):
        truth = {"entities": [entity(
            "acct", "CREDIT_CARD", txns="NOT_YET_ESTABLISHED",
            expectedCreditLimit="50000.00", expectedTotalAmountDue="4321.50",
            expectedPaymentDueDate="2026-08-15",
            expectedStatementPeriodStart="2026-07-01", expectedStatementPeriodEnd="2026-07-31")]}
        rec = record([section(0, 1, "CREDIT_CARD",
                              creditLimit="50000.00", totalAmountDue="4321.50",
                              paymentDueDate="2026-08-15",
                              statementPeriodStart="2026-07-01", statementPeriodEnd="2026-07-31")])

        self.assertEqual(gt.PASS, gt.match(truth, rec)["verdict"])

    def test_one_mismatched_statement_field_among_several_correct_ones_still_fails(self):
        truth = {"entities": [entity(
            "acct", "CREDIT_CARD", txns="NOT_YET_ESTABLISHED",
            expectedCreditLimit="50000.00", expectedTotalAmountDue="4321.50")]}
        rec = record([section(0, 1, "CREDIT_CARD",
                              creditLimit="50000.00", totalAmountDue="WRONG")])

        r = gt.match(truth, rec)

        self.assertEqual(gt.FAIL, r["verdict"])
        self.assertIn("totalAmountDue", r["entities"][0]["detail"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

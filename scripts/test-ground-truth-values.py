#!/usr/bin/env python3
"""The value axis of the ground-truth matcher.

WHY THIS FILE EXISTS SEPARATELY FROM test-ground-truth-match.py
---------------------------------------------------------------
Those fifteen tests are the contract for entity/product/count matching and must keep passing
untouched -- they are the reviewed behaviour. These are the contract for a second, optional axis, and
keeping them apart means a failure says which contract broke.

THE CANONICAL TEST
------------------
correct_row_count_with_a_wrong_amount_fails. That is the exact gap the OCR-2A mutation test exposed:
a document with the right entity, the right product and the right number of transactions passed while
an amount was wrong. It is the shape a recogniser fails in -- the right number of rows with a wrong
digit in one of them -- and the whole reason this axis exists.
"""

import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("gtm", Path(__file__).parent / "ground-truth-match.py")
gtm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gtm)


def rows(*specs):
    return [{"date": d, "amount": a, "direction": x, "currency": "INR"} for d, a, x in specs]


AS_DECLARED = rows(("2026-06-05", "55000.00", "CREDIT"),
                   ("2026-06-10", "2000.00", "DEBIT"))


def truth(expected_values=None, count=2):
    return {"entities": [{
        "id": "savings-primary", "expectedPresence": "DETECTED", "expectedProduct": "SAVINGS",
        "expectedTransactions": count,
        "expectedTransactionValues": AS_DECLARED if expected_values is None else expected_values,
    }]}


def observed(observed_values, source="SYNTHETIC", count=2):
    section = {"index": 0, "rows": count, "suggestedAccountType": "SAVINGS",
               "detectedProduct": "SAVINGS", "accountNumberMasked": None}
    if observed_values is not None:
        section["transactions"] = observed_values
    return {"observed": {"observationSource": source, "sectionDetail": [section]}}


def dim(result, dimension):
    return result["entities"][0]["values"][dimension]["outcome"]


class ValueAxis(unittest.TestCase):

    def test_synthetic_values_matching_ground_truth_pass(self):
        r = gtm.match(truth(), observed(AS_DECLARED))
        self.assertEqual(r["verdict"], gtm.PASS)
        for d in gtm.VALUE_DIMENSIONS:
            self.assertEqual(dim(r, d), gtm.MATCHED, d)

    def test_correct_row_count_with_a_wrong_amount_fails(self):
        """THE canonical regression for this milestone -- the gap OCR-2A's mutation exposed."""
        wrong = rows(("2026-06-05", "35000.00", "CREDIT"), ("2026-06-10", "2000.00", "DEBIT"))
        r = gtm.match(truth(), observed(wrong))
        self.assertEqual(r["verdict"], gtm.FAIL)
        self.assertEqual(dim(r, "amount"), gtm.UNEXPECTED)
        # And the count dimension is untouched: the row count really was right.
        self.assertNotIn("under-extracted", r["entities"][0]["detail"])

    def test_a_changed_date_fails(self):
        wrong = rows(("2026-07-05", "55000.00", "CREDIT"), ("2026-06-10", "2000.00", "DEBIT"))
        r = gtm.match(truth(), observed(wrong))
        self.assertEqual(r["verdict"], gtm.FAIL)
        self.assertEqual(dim(r, "date"), gtm.UNEXPECTED)

    def test_a_debit_read_as_a_credit_fails(self):
        wrong = rows(("2026-06-05", "55000.00", "CREDIT"), ("2026-06-10", "2000.00", "CREDIT"))
        r = gtm.match(truth(), observed(wrong))
        self.assertEqual(r["verdict"], gtm.FAIL)
        self.assertEqual(dim(r, "direction"), gtm.UNEXPECTED)

    def test_an_unobserved_value_is_UNKNOWN_and_never_a_pass_of_that_dimension(self):
        r = gtm.match(truth(), observed(None))
        for d in gtm.VALUE_DIMENSIONS:
            self.assertEqual(dim(r, d), gtm.UNKNOWN, d)
        # Absent must not read as zero, as null-matching-anything, or as NOT_APPLICABLE.
        self.assertNotEqual(dim(r, "amount"), gtm.MATCHED)

    def test_UNKNOWN_is_per_dimension_and_does_not_weaken_the_entity(self):
        """An entity whose product and count agree is not made uncertain by unobservable amounts."""
        r = gtm.match(truth(), observed(None))
        self.assertEqual(r["entities"][0]["outcome"], gtm.MATCHED)
        self.assertEqual(r["entities"][0]["status"], gtm.PASS)
        self.assertEqual(r["verdict"], gtm.PASS)

    def test_a_real_corpus_observation_keeps_its_existing_matching_unchanged(self):
        r = gtm.match(truth(), observed(None, source="REAL_CORPUS"))
        self.assertEqual(r["verdict"], gtm.PASS)
        self.assertEqual(r["observationSource"], gtm.REAL_CORPUS)
        self.assertEqual(dim(r, "amount"), gtm.UNKNOWN)

    def test_an_unlabelled_observation_is_treated_as_real_corpus(self):
        """The safe direction: forgetting to declare loses capability rather than gaining permission."""
        record = {"observed": {"sectionDetail": [
            {"index": 0, "rows": 2, "suggestedAccountType": "SAVINGS"}]}}
        r = gtm.match(truth(), record)
        self.assertEqual(r["observationSource"], gtm.REAL_CORPUS)

    def test_financial_values_on_a_real_corpus_observation_are_refused(self):
        """Structural, not remembered: the boundary holds even if a probe were changed to emit them."""
        r = gtm.match(truth(), observed(AS_DECLARED, source="REAL_CORPUS"))
        self.assertEqual(r["verdict"], gtm.FAIL)
        self.assertIn("REAL_CORPUS", r["entities"][0]["detail"])

    def test_a_value_the_ground_truth_does_not_assert_is_UNKNOWN_not_agreement(self):
        unasserted = [{"date": None, "amount": None, "direction": None, "currency": None}] * 2
        r = gtm.match(truth(expected_values=unasserted), observed(AS_DECLARED))
        for d in gtm.VALUE_DIMENSIONS:
            self.assertEqual(dim(r, d), gtm.UNKNOWN, d)

    def test_a_row_expected_but_not_observed_is_MISSING(self):
        r = gtm.match(truth(), observed(rows(("2026-06-05", "55000.00", "CREDIT"))))
        self.assertEqual(dim(r, "date"), gtm.MISSING)
        self.assertEqual(r["verdict"], gtm.FAIL)


if __name__ == "__main__":
    unittest.main(verbosity=0)

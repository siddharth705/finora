#!/usr/bin/env python3
"""Tests for corpus-diff.py. Run: python3 scripts/test-corpus-diff.py

Stdlib unittest, no dependencies, so this runs anywhere python3 does -- including CI, which has no
access to the real corpus and therefore cannot test the diff any other way.

Every fixture here is SYNTHETIC and hand-built. None is derived from a real statement, and no test
encodes what any real document should produce -- that would put the assumption ADR-004 forbids inside
the test suite instead of the script, which is not an improvement.
"""

import importlib.util
import re
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("corpus_diff",
                                               Path(__file__).resolve().parent / "corpus-diff.py")
cd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cd)


def section(index, rows, product="SAVINGS", masked="****0001", verification=None, **kw):
    s = {"index": index, "rows": rows, "detectedProduct": product,
         "suggestedAccountType": product, "accountNumberMasked": masked,
         "productConfidence": 0.9, "productNeedsReview": False,
         "verification": verification or {"BALANCE_CHAIN": "VERIFIED"}}
    s.update(kw)
    return s


def record(name="synthetic.pdf", rows=None, sections=None, classification="PARSED_COMPLETE",
           verification=None, **observed):
    """A minimally-shaped corpus record. `rows` is the per-section row list."""
    rows = [0] if rows is None else rows
    detail = sections if sections is not None else [section(i, r) for i, r in enumerate(rows)]
    obs = {"pages": 4, "extractedChars": 5000, "positionedRuns": 200,
           "sections": len(detail), "rows": sum(s["rows"] for s in detail),
           "banks": ["SYNTHETIC"], "capabilities": ["WRAPPED_DESCRIPTION:SUCCESS"],
           "layoutFingerprint": "FP-1-AAAA0000", "sectionDetail": detail,
           "verification": verification or {"BALANCE_CHAIN": "VERIFIED"}}
    obs.update(observed)
    return {"schema": 1, "file": name, "status": "ok", "observed": obs,
            "derived": {"documentClassification": classification,
                        "suspectedIncompleteByPageRatio": False}}


def severities(changes, dimension=None):
    return {c["severity"] for c in changes if dimension is None or c["dimension"] == dimension}


def dimensions(changes):
    return {c["dimension"] for c in changes}


class RowCounts(unittest.TestCase):

    def test_a_row_count_decrease_is_a_regression(self):
        """The one every other test exists to support: fewer rows after a change is caught."""
        changes = cd.compare_record(record(rows=[58]), record(rows=[41]))

        self.assertIn("rows", dimensions(changes))
        self.assertEqual({cd.REGRESSION}, severities(changes, "rows"))
        self.assertEqual({cd.REGRESSION}, severities(changes, "section[0].rows"))

    def test_the_diff_exits_non_zero_on_a_row_count_decrease(self):
        """The exit code is the gate, so it is tested as such and not just the change list."""
        d = cd.diff({"s.pdf": record(rows=[58])}, {"s.pdf": record(rows=[41])})

        self.assertEqual(1, cd.tally(d)[cd.REGRESSION] and 1)
        self.assertGreater(cd.tally(d)[cd.REGRESSION], 0)

    def test_rows_moving_between_sections_is_caught_at_a_constant_total(self):
        """[75,15,0] -> [15,75,0]. 90 rows, 3 sections, 2 row-bearing sections in both runs.

        This is an RD's transactions being attributed to the savings account: every document-level
        figure is identical and the money is on the wrong product. If the diff misses this, Step 2b
        recorded per-section detail for nothing.
        """
        before, after = record(rows=[75, 15, 0]), record(rows=[15, 75, 0])
        self.assertEqual(before["observed"]["rows"], after["observed"]["rows"])       # both 90
        self.assertEqual(before["observed"]["sections"], after["observed"]["sections"])

        changes = cd.compare_record(before, after)

        self.assertNotIn("rows", dimensions(changes))          # the document total did not move
        self.assertEqual({cd.REGRESSION}, severities(changes, "section[0].rows"))
        self.assertEqual({cd.IMPROVEMENT}, severities(changes, "section[1].rows"))

    def test_a_row_count_increase_is_an_improvement_not_a_regression(self):
        changes = cd.compare_record(record(rows=[3]), record(rows=[61]))

        self.assertEqual({cd.IMPROVEMENT}, severities(changes, "rows"))
        self.assertNotIn(cd.REGRESSION, severities(changes))

    def test_identical_runs_produce_no_changes(self):
        self.assertEqual([], cd.compare_record(record(rows=[75, 0, 0]), record(rows=[75, 0, 0])))


class SectionStructure(unittest.TestCase):

    def test_a_section_count_change_suppresses_positional_comparison_and_says_so(self):
        """Aligning [75,0,0] against [75,0] by index would report a section vanishing AND every
        later index shifting. The structural change is the finding; the rest is noise."""
        changes = cd.compare_record(record(rows=[75, 0, 0]), record(rows=[75, 0]))

        self.assertIn("sectionStructure", dimensions(changes))
        self.assertFalse([d for d in dimensions(changes) if d.startswith("section[")])
        detail = next(c["detail"] for c in changes if c["dimension"] == "sectionStructure")
        self.assertIn("suppressed", detail)

    def test_a_zero_row_section_disappearing_is_visible_with_no_row_change(self):
        """Shivani's RD and FD are zero-row sections. Dropping them changes no row count."""
        before, after = record(rows=[75, 0, 0]), record(rows=[75])
        self.assertEqual(before["observed"]["rows"], after["observed"]["rows"])       # both 75

        changes = cd.compare_record(before, after)

        self.assertIn("sections", dimensions(changes))
        self.assertIn("sectionStructure", dimensions(changes))

    def test_a_product_reclassification_within_a_section_is_reported(self):
        before = record(sections=[section(0, 0, product="SAVINGS")])
        after = record(sections=[section(0, 0, product="RECURRING_DEPOSIT")])

        changes = cd.compare_record(before, after)

        self.assertIn("section[0].detectedProduct", dimensions(changes))
        self.assertEqual({cd.REVIEW}, severities(changes, "section[0].detectedProduct"))


class Identity(unittest.TestCase):

    def test_losing_a_masked_account_number_is_a_regression(self):
        """Identity is the input to ProductIdentity; without it re-import cannot resolve."""
        before = record(sections=[section(0, 5, masked="****4291")])
        after = record(sections=[section(0, 5, masked=None)])

        changes = cd.compare_record(before, after)

        self.assertEqual({cd.REGRESSION}, severities(changes, "section[0].accountNumberMasked"))

    def test_gaining_a_masked_account_number_is_an_improvement(self):
        changes = cd.compare_record(record(sections=[section(0, 5, masked=None)]),
                                    record(sections=[section(0, 5, masked="****4291")]))

        self.assertEqual({cd.IMPROVEMENT}, severities(changes, "section[0].accountNumberMasked"))


class Verification(unittest.TestCase):

    def test_a_rule_going_from_verified_to_warning_is_a_regression(self):
        changes = cd.compare_record(record(verification={"COLUMN_AMBIGUITY": "VERIFIED"}),
                                    record(verification={"COLUMN_AMBIGUITY": "WARNING"}))

        self.assertEqual({cd.REGRESSION}, severities(changes, "verification.COLUMN_AMBIGUITY"))

    def test_a_rule_going_from_warning_to_failed_is_a_regression(self):
        changes = cd.compare_record(record(verification={"BALANCE_CHAIN": "WARNING"}),
                                    record(verification={"BALANCE_CHAIN": "FAILED"}))

        self.assertEqual({cd.REGRESSION}, severities(changes, "verification.BALANCE_CHAIN"))

    def test_a_rule_becoming_not_applicable_is_flagged_for_review_not_ignored(self):
        """A rule that stops applying has stopped checking. Silence here would hide that."""
        changes = cd.compare_record(record(verification={"BALANCE_CHAIN": "VERIFIED"}),
                                    record(verification={"BALANCE_CHAIN": "NOT_APPLICABLE"}))

        self.assertEqual({cd.REVIEW}, severities(changes, "verification.BALANCE_CHAIN"))

    def test_per_section_verification_is_compared_per_section(self):
        """A warning on section 1 must not be maskable by section 0 passing."""
        before = record(sections=[section(0, 5, verification={"COLUMN_AMBIGUITY": "VERIFIED"}),
                                  section(1, 0, verification={"COLUMN_AMBIGUITY": "VERIFIED"})])
        after = record(sections=[section(0, 5, verification={"COLUMN_AMBIGUITY": "VERIFIED"}),
                                 section(1, 0, verification={"COLUMN_AMBIGUITY": "WARNING"})])

        changes = cd.compare_record(before, after)

        self.assertEqual({cd.REGRESSION},
                         severities(changes, "section[1].verification.COLUMN_AMBIGUITY"))
        self.assertNotIn("section[0].verification.COLUMN_AMBIGUITY", dimensions(changes))

    def test_a_rule_appearing_is_flagged_for_review(self):
        changes = cd.compare_record(record(verification={}),
                                    record(verification={"SUMMARY_TOTALS": "VERIFIED"}))

        self.assertEqual({cd.REVIEW}, severities(changes, "verification.SUMMARY_TOTALS"))


class Classification(unittest.TestCase):

    def test_dropping_a_tier_is_a_regression(self):
        changes = cd.compare_record(record(rows=[58], classification="PARSED_COMPLETE"),
                                    record(rows=[58], classification="LAYOUT_UNSUPPORTED"))

        self.assertEqual({cd.REGRESSION}, severities(changes, "documentClassification"))

    def test_rising_a_tier_is_an_improvement(self):
        changes = cd.compare_record(record(rows=[58], classification="COLUMNS_AMBIGUOUS"),
                                    record(rows=[58], classification="PARSED_COMPLETE"))

        self.assertEqual({cd.IMPROVEMENT}, severities(changes, "documentClassification"))

    def test_movement_within_a_tier_is_review_because_the_enum_asserts_no_order(self):
        changes = cd.compare_record(record(rows=[58], classification="COLUMNS_AMBIGUOUS"),
                                    record(rows=[58], classification="PARSED_INCOMPLETE"))

        self.assertEqual({cd.REVIEW}, severities(changes, "documentClassification"))

    def test_an_unrecognised_classification_is_review_and_never_silently_ranked(self):
        """The enum gains a value and this script does not. Reporting "fine" would be the failure."""
        changes = cd.compare_record(record(rows=[58], classification="PARSED_COMPLETE"),
                                    record(rows=[58], classification="ENCRYPTED_DOCUMENT"))

        self.assertEqual({cd.REVIEW}, severities(changes, "documentClassification"))
        self.assertIn("corpus-diff.py needs its tier",
                      next(c["detail"] for c in changes
                           if c["dimension"] == "documentClassification"))

    def test_every_classification_in_the_java_enum_has_a_tier(self):
        """Drift guard. Read from the enum source so adding a value there fails here."""
        src = (Path(__file__).resolve().parent.parent / "backend/src/main/java/com/finora/imports"
               / "analysis/DocumentClassification.java").read_text()
        # An enum constant is a bare SCREAMING_CASE identifier alone on its line, which distinguishes
        # a declaration from the same name appearing inside a {@link #...} in the javadoc above it.
        body = src[src.index("public enum DocumentClassification"):src.index("private static final")]
        constants = set(re.findall(r"^ {4}([A-Z][A-Z_]{3,})\s*[,;]\s*$", body, re.MULTILINE))

        self.assertEqual(7, len(constants), f"parsed the enum wrongly: {sorted(constants)}")
        self.assertEqual(constants, set(cd.TIERS),
                         "corpus-diff.py's tiers and DocumentClassification have drifted apart")


class Status(unittest.TestCase):

    def test_a_statement_that_starts_erroring_is_a_regression(self):
        after = {"schema": 1, "file": "synthetic.pdf", "status": "error",
                 "error": {"type": "Timeout", "message": "probe exceeded 180s"}}

        changes = cd.compare_record(record(rows=[58]), after)

        self.assertEqual({cd.REGRESSION}, severities(changes, "status"))

    def test_nothing_below_the_error_boundary_is_compared(self):
        """Comparing rows against a record that has none would invent changes."""
        after = {"schema": 1, "file": "s.pdf", "status": "error", "error": {"type": "Timeout"}}

        self.assertEqual(["status"], [c["dimension"] for c in cd.compare_record(record(), after)])

    def test_a_statement_that_stops_erroring_is_an_improvement(self):
        before = {"schema": 1, "file": "s.pdf", "status": "error", "error": {"type": "Timeout"}}

        self.assertEqual({cd.IMPROVEMENT}, severities(cd.compare_record(before, record())))


class CorpusMembership(unittest.TestCase):

    def test_added_and_removed_statements_are_reported_but_are_not_regressions(self):
        """Growing the corpus is how the net improves; it must not read as a parser regression."""
        d = cd.diff({"a.pdf": record("a.pdf")},
                    {"a.pdf": record("a.pdf"), "b.pdf": record("b.pdf")})

        self.assertEqual(["b.pdf"], d["added"])
        self.assertEqual([], d["removed"])
        self.assertEqual(0, cd.tally(d)[cd.REGRESSION])

    def test_a_removed_statement_is_reported(self):
        d = cd.diff({"a.pdf": record("a.pdf"), "b.pdf": record("b.pdf")},
                    {"a.pdf": record("a.pdf")})

        self.assertEqual(["b.pdf"], d["removed"])


class Genericity(unittest.TestCase):

    def test_the_script_contains_no_institution_name_and_no_corpus_filename(self):
        """ADR-004: a diff tool that knows what each file should produce IS the assumption we are
        refusing to build. Enforced here rather than trusted."""
        src = (Path(__file__).resolve().parent / "corpus-diff.py").read_text()
        code = "\n".join(line for line in src.splitlines()
                         if not line.lstrip().startswith("#"))
        code = code[code.index('"""', code.index('"""') + 3):]        # drop the module docstring
        for token in ("hdfc", "icici", "axis", "hsbc", "kotak", "canara", "bandhan",
                      "pnb", "union bank", ".pdf"):
            self.assertNotIn(token, code.lower(),
                             f"corpus-diff.py mentions {token!r} outside its docstring")


if __name__ == "__main__":
    unittest.main(verbosity=2)

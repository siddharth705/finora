#!/usr/bin/env python3
"""Mechanism test for corpus-run.py's in-repo refusal. Run: python3 scripts/test-corpus-run.py

Only the refusal logic is unit-tested here -- everything else in corpus-run.py needs a real corpus
and cannot reach CI, same split every other test-*.py in this directory already uses.
"""

import importlib.util
import unittest
from pathlib import Path

_s = importlib.util.spec_from_file_location(
    "cr", Path(__file__).resolve().parent / "corpus-run.py")
cr = importlib.util.module_from_spec(_s)
_s.loader.exec_module(cr)

_rgt = importlib.util.spec_from_file_location(
    "rgt", Path(__file__).resolve().parent / "run-corpus-ground-truth.py")
rgt = importlib.util.module_from_spec(_rgt)
_rgt.loader.exec_module(rgt)


class RefuseIfInsideRepoIsSkippedOnlyWithTheExplicitFlag(unittest.TestCase):

    def test_a_path_inside_the_repo_is_refused_by_default(self):
        # This script's own directory stands in for "inside the repo" -- never the real corpus
        # location, which this test must not reference even as a string.
        inside_repo_path = Path(__file__).parent
        with self.assertRaises(SystemExit):
            cr._refuse_if_inside_repo(inside_repo_path, "corpus")

    def test_the_explicit_flag_skips_the_refusal(self):
        inside_repo_path = Path(__file__).parent
        cr._refuse_if_inside_repo(inside_repo_path, "corpus",
                                   allow_in_repo_synthetic_corpus=True)   # does not raise

    def test_a_path_outside_the_repo_is_never_refused(self):
        outside_repo_path = Path("/tmp")
        cr._refuse_if_inside_repo(outside_repo_path, "corpus")           # does not raise


class RunCorpusGroundTruthAppliesTheSameOptIn(unittest.TestCase):
    """run-corpus-ground-truth.py's _refuse_if_inside_repo is a separate copy ("reused verbatim",
    per its own doc comment) rather than an import of corpus-run.py's -- so it needs the identical
    behaviour proven again here, not assumed from the sibling test class above."""

    def test_a_path_inside_the_repo_is_refused_by_default(self):
        inside_repo_path = Path(__file__).parent
        with self.assertRaises(SystemExit):
            rgt._refuse_if_inside_repo(inside_repo_path, "corpus")

    def test_the_explicit_flag_skips_the_refusal(self):
        inside_repo_path = Path(__file__).parent
        rgt._refuse_if_inside_repo(inside_repo_path, "corpus",
                                    allow_in_repo_synthetic_corpus=True)   # does not raise


if __name__ == "__main__":
    unittest.main(verbosity=2)

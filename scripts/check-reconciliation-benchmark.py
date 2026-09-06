#!/usr/bin/env python3
"""Fails CI if the reconciliation accuracy benchmark regresses -- never on its known failures.

WHY THIS EXISTS
---------------
docs/proposals/reconciliation-benchmark/ built a 59-scenario benchmark against
ReconciliationService and, across five measured PRs, moved it from 69.5% to 84.7% pass rate.
9 scenarios are still failing ON PURPOSE -- each encodes what a CORRECT verdict is, independent of
what the engine currently does, and a red assertion there is a documented, evidenced finding, not a
bug in the test. See that folder's README.md for the full "why a red test is not a broken build"
reasoning.

That means a plain "all benchmark tests must pass" gate is impossible (it would fail on the very
scenarios the benchmark exists to keep visible) and "no benchmark test may fail" is the wrong
question. The right question, and the only one this script answers, is: **did a scenario that used
to pass just start failing?** That is what "regression" means here, and it is answered by
comparing the CURRENT failing set against scripts/reconciliation-benchmark-baseline.txt, a
committed list of exactly which scenarios are known-red today -- not by a bare pass/fail count,
which cannot tell a real regression apart from an unrelated fix that coincidentally keeps the
total the same (test A silently breaks, test B silently gets fixed, the failure COUNT never
moves). Comparing the actual SET of failing test names is what closes that gap.

An improvement (a baseline-listed scenario that now passes) is never a failure here -- it is
printed as a suggestion to update the baseline file, the same "ratchet, never block on getting
better" spirit scripts/check-fixture-hygiene.sh's --tree-ratchet already uses, adapted from a bare
count to a named set because these are identified JUnit scenarios, not free-text pattern matches.

WHAT MAKES IT FAIL
------------------
- The surefire report directory is missing, or contains none of the six benchmark classes: the
  test step did not actually run the benchmark, whatever its own exit code was -- the exact
  "smaller number nobody was looking at" shape scripts/summarize-surefire.py's own docstring
  documents two real incidents of.
- Any scenario fails that is NOT in the baseline file: a real regression. Printed by name, with the
  fix instruction (revert the change, or if the new failure is itself an accepted, evidenced
  finding, add it to the baseline in the same PR that changes the behavior -- never as a way to
  silence an accidental regression).

It does NOT fail on a scenario that is failing and already in the baseline -- that is the
documented status quo, not new information.
"""

import argparse
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = REPO_ROOT / "backend" / "target" / "surefire-reports"
BASELINE_FILE = REPO_ROOT / "scripts" / "reconciliation-benchmark-baseline.txt"

# The six benchmark classes docs/proposals/reconciliation-benchmark/README.md documents. Checked
# by full presence, not just "at least one" -- backend/pom.xml's surefire <includes> deliberately
# do not match any of these (they end in "Benchmark", not "Test"/"Tests"/"TestCase"), so the CI
# step that runs them has to name them explicitly with -Dtest, and a typo or a dropped class from
# that list would silently narrow what this gate protects -- the same failure shape
# summarize-surefire.py's own docstring names for the *IT suite going quiet for months.
BENCHMARK_CLASSES = {
    "com.finora.service.DuplicateDetectionBenchmark",
    "com.finora.service.TransferBenchmark",
    "com.finora.service.InvestmentTransferBenchmark",
    "com.finora.service.RefundReversalBenchmark",
    "com.finora.service.GmailMatchingBenchmark",
    "com.finora.service.CreditCardPaymentBenchmark",
}


def parse_failing_tests(report_dir: Path) -> tuple[set[str], set[str]]:
    """Returns (failing_test_ids, classes_seen). A test id is "classname.testname"."""
    failing = set()
    classes_seen = set()
    for path in sorted(report_dir.glob("TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"warning: could not parse {path.name}: {exc}", file=sys.stderr)
            continue
        if root.tag != "testsuite":
            continue
        classname = root.get("name", "")
        if classname not in BENCHMARK_CLASSES:
            continue
        classes_seen.add(classname)
        for testcase in root.findall("testcase"):
            test_id = f"{classname}.{testcase.get('name')}"
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                failing.add(test_id)
    return failing, classes_seen


def _display_path(path: Path) -> str:
    """path relative to the repo root when possible, else its plain string -- the self-test swaps
    BASELINE_FILE for a temp-directory path that isn't under REPO_ROOT at all."""
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def load_baseline(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    lines = path.read_text(encoding="utf-8").splitlines()
    return {line.strip() for line in lines if line.strip() and not line.strip().startswith("#")}


def evaluate(failing: set[str], classes_seen: set[str], baseline: set[str]) -> tuple[int, str]:
    lines = []
    missing_classes = BENCHMARK_CLASSES - classes_seen
    if missing_classes:
        lines.append("BLOCKED: the reconciliation benchmark did not fully run.")
        lines.append(
            "Missing from the surefire reports: " + ", ".join(sorted(missing_classes)) + "."
        )
        lines.append(
            "The CI step that runs these classes names them explicitly with -Dtest (they are "
            "deliberately excluded from backend/pom.xml's surefire <includes> -- see "
            "docs/proposals/reconciliation-benchmark/README.md). Check that step's -Dtest list."
        )
        return 1, "\n".join(lines)

    regressions = sorted(failing - baseline)
    improvements = sorted(baseline - failing)

    lines.append("## Reconciliation benchmark")
    lines.append("")
    lines.append(f"{len(failing)} of the known scenarios are currently failing; "
                 f"{len(baseline)} are in the committed baseline.")

    if regressions:
        lines.append("")
        lines.append(f"### REGRESSION: {len(regressions)} newly-failing scenario(s)")
        lines.append("")
        for test_id in regressions:
            lines.append(f"- `{test_id}`")
        lines.append("")
        lines.append(
            "This scenario used to pass and does not anymore. If this change genuinely made the "
            "engine wrong here, fix it before merging. If the new failure is itself a deliberate, "
            "evidenced change in expected behavior, add it to "
            f"{_display_path(BASELINE_FILE)} in this same PR -- never as a way to silence "
            "an accidental regression."
        )

    if improvements:
        lines.append("")
        lines.append(f"### {len(improvements)} scenario(s) now pass that are still listed as known failures")
        lines.append("")
        for test_id in improvements:
            lines.append(f"- `{test_id}`")
        lines.append("")
        lines.append(
            f"Not a failure -- but {_display_path(BASELINE_FILE)} is now stale for these. "
            "Remove them from the baseline in this PR so the file keeps tracking reality, per "
            "docs/proposals/reconciliation-benchmark/remaining-failures-classification.md's own "
            "re-measure-after-every-fix discipline."
        )

    if not regressions and not improvements:
        lines.append("")
        lines.append("Matches the baseline exactly. No change.")

    return (1 if regressions else 0), "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true",
                         help="Run against synthetic surefire XML instead of a real test run, and "
                              "assert this script still catches a regression, still lets an "
                              "improvement through, and still detects a hollow run. Exits 0 only "
                              "if every assertion holds -- a gate nobody has falsified is a gate "
                              "nobody knows is running.")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not REPORT_DIR.is_dir():
        print(f"BLOCKED: no surefire reports at {REPORT_DIR.relative_to(REPO_ROOT)}.")
        print("The benchmark test step did not produce results, whatever exit code it returned.")
        return 1

    failing, classes_seen = parse_failing_tests(REPORT_DIR)
    baseline = load_baseline(BASELINE_FILE)
    code, summary = evaluate(failing, classes_seen, baseline)
    print(summary)
    import os
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    return code


def _write_suite(dir_: Path, classname: str, testcases: dict[str, bool]) -> None:
    """testcases: {name: is_failing}."""
    failures = sum(1 for failing in testcases.values() if failing)
    root = ET.Element("testsuite", {
        "name": classname, "tests": str(len(testcases)),
        "failures": str(failures), "errors": "0", "skipped": "0",
    })
    for name, is_failing in testcases.items():
        tc = ET.SubElement(root, "testcase", {"name": name, "classname": classname, "time": "0.01"})
        if is_failing:
            ET.SubElement(tc, "failure", {"message": "synthetic", "type": "org.opentest4j.AssertionFailedError"})
    ET.ElementTree(root).write(dir_ / f"TEST-{classname}.xml", encoding="UTF-8", xml_declaration=True)


def self_test() -> int:
    global REPORT_DIR, BASELINE_FILE
    original_report_dir, original_baseline = REPORT_DIR, BASELINE_FILE
    tmp = Path(tempfile.mkdtemp(prefix="recon-benchmark-selftest-"))
    try:
        REPORT_DIR = tmp / "surefire-reports"
        REPORT_DIR.mkdir()
        BASELINE_FILE = tmp / "baseline.txt"

        # Fixture: one benchmark class, three scenarios -- one known-failing (in baseline), one
        # passing, and (per case below) a third that varies.
        one_class = BENCHMARK_CLASSES - {"com.finora.service.CreditCardPaymentBenchmark",
                                          "com.finora.service.GmailMatchingBenchmark",
                                          "com.finora.service.InvestmentTransferBenchmark",
                                          "com.finora.service.RefundReversalBenchmark",
                                          "com.finora.service.TransferBenchmark"}
        target_class = next(iter(one_class))

        # Case 1: exact match to baseline -- must pass clean, no regression, no improvement noted.
        for path in REPORT_DIR.glob("*.xml"):
            path.unlink()
        for c in BENCHMARK_CLASSES:
            _write_suite(REPORT_DIR, c, {"knownGap": c == target_class, "alwaysPasses": False})
        BASELINE_FILE.write_text(f"{target_class}.knownGap\n")
        failing, seen = parse_failing_tests(REPORT_DIR)
        code, _ = evaluate(failing, seen, load_baseline(BASELINE_FILE))
        assert code == 0, "case 1 (exact baseline match) should not fail CI"

        # Case 2: a scenario NOT in the baseline starts failing -- must be caught as a regression.
        for path in REPORT_DIR.glob("*.xml"):
            path.unlink()
        for c in BENCHMARK_CLASSES:
            _write_suite(REPORT_DIR, c, {
                "knownGap": c == target_class,
                "alwaysPasses": c == target_class,  # now ALSO failing -- this is the regression
            })
        failing, seen = parse_failing_tests(REPORT_DIR)
        code, summary = evaluate(failing, seen, load_baseline(BASELINE_FILE))
        assert code == 1, "case 2 (new failure outside baseline) must block CI"
        assert "alwaysPasses" in summary, "the regressing test's own name must appear in the report"

        # Case 3: the known-baseline gap gets fixed -- must NOT block CI, only note the improvement.
        for path in REPORT_DIR.glob("*.xml"):
            path.unlink()
        for c in BENCHMARK_CLASSES:
            _write_suite(REPORT_DIR, c, {"knownGap": False, "alwaysPasses": False})
        failing, seen = parse_failing_tests(REPORT_DIR)
        code, summary = evaluate(failing, seen, load_baseline(BASELINE_FILE))
        assert code == 0, "case 3 (a known failure now passing) must not block CI"
        assert "knownGap" in summary, "the improvement must still be named so the baseline gets updated"

        # Case 4: a hollow run (one benchmark class never ran at all) -- must block, not pass silently.
        for path in REPORT_DIR.glob("*.xml"):
            path.unlink()
        for c in BENCHMARK_CLASSES - {target_class}:
            _write_suite(REPORT_DIR, c, {"alwaysPasses": False})
        failing, seen = parse_failing_tests(REPORT_DIR)
        code, summary = evaluate(failing, seen, load_baseline(BASELINE_FILE))
        assert code == 1, "case 4 (a benchmark class missing from the run) must block CI"
        assert target_class in summary, "the missing class must be named"

        print("self-test: all 4 cases passed (clean match, regression caught, improvement waived, "
              "hollow run blocked)")
        return 0
    except AssertionError as exc:
        print(f"SELF-TEST FAILED: {exc}", file=sys.stderr)
        return 1
    finally:
        REPORT_DIR, BASELINE_FILE = original_report_dir, original_baseline
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())

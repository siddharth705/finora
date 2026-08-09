#!/usr/bin/env python3
"""Score OCR engines on the ledger they produce, not on the characters they read.

Renders a declared statement, rasterises it, hands the image to each engine, pushes the recognised
runs through the REAL parser, and judges the result with the same matcher that judges native
extraction. A row of this scorecard is a claim about the whole pipeline.

Why not character accuracy
--------------------------
An engine that transcribes every glyph correctly and reports positions one column off produces a
perfect character score and an inverted ledger -- 55,000.00 read as money leaving the account
instead of arriving. That is the failure this project already shipped once. Character accuracy
cannot see it; the parser can, so the parser is what scores.

Calibration
-----------
The stub engines are not candidates. They exist so the scorecard has to demonstrate that it can
fail before anyone reads a number off it:

    ceiling          the source text layer -- what a flawless engine would score.  MUST PASS
    misread-amount   one digit wrong, everything else perfect.                     MUST FAIL
    drifted-column   perfect characters, values one column left.                   MUST FAIL
    blind            recognises nothing.                                           MUST FAIL

Run with --calibrate to assert exactly that and nothing else. If calibration does not hold, no
engine result from this harness means anything, which is why it runs first and gates the rest.

Usage
-----
    ./mvnw -o test-compile          (in backend/, once)
    scripts/ocr-scorecard.py --calibrate
    scripts/ocr-scorecard.py ceiling misread-amount drifted-column
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BACKEND = REPO / "backend"
EMITTER = "com.finora.imports.pdf.ocr.OcrScorecardEmitter"
MATCHER = HERE / "ground-truth-match.py"
CLASSPATH_CACHE = BACKEND / "target" / "corpus-classpath.txt"

# What each stub must score for the harness itself to be trustworthy. Deliberately not a list of
# engines to run -- it is an assertion about the ruler.
CALIBRATION = {
    "ceiling": "PASS",
    "misread-amount": "FAIL",
    "drifted-column": "FAIL",
    "blind": "FAIL",
}

# 300, not the 150 that ScannedPdfFixture produces. Measured: at 150 DPI ledger equivalence never
# exceeds 7 of 10 layouts at any assembly threshold, and the three that fail there fail for all of
# them -- the limit is pixels, not grouping. See OcrEvaluation.OCR_DPI.
DEFAULT_DPI = 300


def classpath():
    test_classes = BACKEND / "target" / "test-classes"
    if not test_classes.is_dir():
        sys.exit("No target/test-classes. Run: cd backend && ./mvnw -o test-compile")
    if not CLASSPATH_CACHE.is_file():
        subprocess.run(["./mvnw", "-q", "-o", "dependency:build-classpath",
                        f"-Dmdep.outputFile={CLASSPATH_CACHE}", "-Dmdep.includeScope=test"],
                       cwd=BACKEND, check=False, capture_output=True)
    if not CLASSPATH_CACHE.is_file():
        sys.exit("could not resolve the test classpath")
    return f"{test_classes}:{BACKEND / 'target' / 'classes'}:{CLASSPATH_CACHE.read_text().strip()}"


def emit(cp, workdir, engines, dpi, scenario="baseline"):
    """Ground truth, the scanned image, and one observation per engine."""
    r = subprocess.run(["java", "-cp", cp, EMITTER, str(workdir), str(dpi), scenario] + engines,
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"emitter failed:\n{r.stdout}\n{r.stderr}")
    return r.stdout


def judge(workdir, engine):
    """The OCR-2B matcher, unmodified.

    Reimplementing the comparison for OCR would let an OCR result be graded on a curve. Whatever the
    native pipeline has to satisfy, a recogniser has to satisfy identically -- that equivalence is
    the entire architectural claim being tested, so it is enforced by using the same program rather
    than by asserting it in a comment.
    """
    r = subprocess.run([sys.executable, str(MATCHER), str(workdir / "ground-truth.json"),
                        str(workdir / f"observed-{engine}.json")], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        sys.exit(f"matcher produced no verdict for {engine}:\n{r.stdout}\n{r.stderr}")


# The axes an engine is judged on, in the order a reader cares about them. Direction sits beside
# amount rather than under "other" because a correct number on the wrong side of the ledger is the
# failure this project actually shipped.
DIMENSIONS = ("date", "amount", "direction", "currency")


def axes(result):
    """Per-dimension outcomes, which is where an engine's real shortfall shows.

    Read from the entity's ``values`` rather than its ``outcome``: ``outcome`` reports whether the
    ENTITY was paired with the right declared entity, and stays MATCHED for an account that was
    correctly identified and then read wrongly. An earlier version of this report keyed on it and
    printed "every declared value, as declared" beside a FAIL verdict.
    """
    worst = {d: "MATCHED" for d in DIMENSIONS}
    notes = []
    for entity in result.get("entities", []):
        for dimension, verdict in (entity.get("values") or {}).items():
            if dimension in worst and verdict.get("outcome") != "MATCHED":
                worst[dimension] = verdict.get("outcome", "?")
        if entity.get("status") not in (None, "PASS"):
            notes.append(f"{entity.get('id')}: {entity.get('detail')}")
    return worst, notes


def run(engines, dpi, scenario="baseline"):
    cp = classpath()
    with tempfile.TemporaryDirectory(prefix="ocr-scorecard-") as tmp:
        workdir = Path(tmp)
        stats = emit(cp, workdir, engines, dpi, scenario)
        return {e: judge(workdir, e) for e in engines}, stats


# What each scenario must produce for the scorecard to mean anything. A mutation scenario prints a
# document that disagrees with truth, so an engine reading it CORRECTLY must still be judged wrong --
# that is what separates "the engine read the statement" from "the harness cannot fail".
SCENARIOS = {
    "baseline": "PASS",
    "wrong-amount": "FAIL",
    "wrong-direction": "FAIL",
    "multi-page": "PASS",
}


def benchmark(engine, dpi):
    """Every scenario against one engine, as the acceptance table."""
    rows, ok = [], True
    for scenario, expected in SCENARIOS.items():
        verdicts, stats = run([engine], dpi, scenario)
        result = verdicts[engine]
        got = result.get("verdict", "?")
        worst, notes = axes(result)
        passed = got == expected
        ok = ok and passed
        rows.append((scenario, expected, got, "OK" if passed else "WRONG", worst, notes,
                     stats.strip()))

    width = 74
    print(f"{'scenario':<17}{'expect':<8}{'got':<7}{'':<7}"
          + "".join(f"{d:<11}" for d in DIMENSIONS))
    print("-" * width)
    for scenario, expected, got, mark, worst, _, _ in rows:
        print(f"{scenario:<17}{expected:<8}{got:<7}{mark:<7}"
              + "".join(f"{worst[d]:<11}" for d in DIMENSIONS))
    print()
    for scenario, _, _, _, _, notes, stats in rows:
        print(f"{scenario}: {stats}")
        for n in notes:
            print(f"    {n}")
    print()
    print(("BENCHMARK HOLDS: correct documents pass and mutated ones fail."
           if ok else "BENCHMARK BROKEN: a scenario did not behave as required."))
    return 0 if ok else 1


def report(verdicts, stats):
    print(stats.rstrip())
    print()
    header = f"{'engine':<20}{'verdict':<9}" + "".join(f"{d:<12}" for d in DIMENSIONS)
    print(header)
    print("-" * len(header))

    explanations = []
    for engine, result in verdicts.items():
        worst, notes = axes(result)
        print(f"{engine:<20}{result.get('verdict', '?'):<9}"
              + "".join(f"{worst[d]:<12}" for d in DIMENSIONS))
        explanations += [f"  {engine}: {n}" for n in notes]

    if explanations:
        print("\nwhy:")
        print("\n".join(explanations))


def calibrate():
    verdicts, stats = run(list(CALIBRATION), DEFAULT_DPI)
    report(verdicts, stats)
    print()

    wrong = {e: (verdicts[e].get("verdict"), expected)
             for e, expected in CALIBRATION.items() if verdicts[e].get("verdict") != expected}
    if wrong:
        for engine, (got, expected) in wrong.items():
            print(f"CALIBRATION FAILED  {engine}: expected {expected}, got {got}")
        print("\nNo engine result from this harness is meaningful until this holds.")
        return 1

    print("Calibration holds: a flawless read passes, and a one-digit misread, a one-column "
          "drift and a blind engine all fail.")
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--benchmark" in sys.argv:
        return benchmark(args[0] if args else "tesseract", DEFAULT_DPI)
    if "--calibrate" in sys.argv or not args:
        return calibrate()
    verdicts, stats = run(args, DEFAULT_DPI)
    report(verdicts, stats)
    return 0


if __name__ == "__main__":
    sys.exit(main())

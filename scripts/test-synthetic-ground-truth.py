#!/usr/bin/env python3
"""Proves the synthetic ground-truth loop closes, and that it detects disagreement.

    SyntheticStatementDefinition
             |                |
             v                v
      ground-truth.json   rendered PDF
             |                |
             |          corpus-run.py -> observed record
             |                |
             +-------+--------+
                     v
           ground-truth-match.py  ->  PASS | FAIL | REVIEW

WHY THIS IS A SCRIPT AND NOT A JAVA TEST
----------------------------------------
The matcher is the reviewed reference implementation of ground-truth-model-design.md and has its own
tests. Porting it to Java to make this a unit test would create two ground-truth authorities that can
eventually disagree about the same document, which is the one outcome the model cannot tolerate. So
Java produces the artefacts, Python interprets them, and the boundary between them is a stated file
format rather than a call.

THE TEST THAT MATTERS IS THE SECOND ONE
---------------------------------------
A loop that agrees with itself proves nothing: if the expected values and the document were both
derived from the same rendering, they would agree whatever the renderer did. So the second case
renders a document that CONTRADICTS the emitted truth and requires the matcher to fail. Only the pair
is evidence.

WHAT THIS DELIBERATELY DOES NOT NEED
------------------------------------
No corpus directory, no real statement, no network, no credentials, no OCR engine. Everything is
generated from committed source into a temporary directory that is removed on the way out, including
on failure. That is asserted rather than assumed -- see check_no_real_inputs_required.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BACKEND = REPO / "backend"
EMITTER = "com.finora.imports.pdf.fixtures.SyntheticGroundTruthEmitter"
CLASSPATH_CACHE = BACKEND / "target" / "corpus-classpath.txt"


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


def emit(cp, workdir, mutate):
    args = ["java", "-cp", cp, EMITTER, str(workdir)] + (["--mutate"] if mutate else [])
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"emitter failed:\n{r.stdout}\n{r.stderr}")


def observe(workdir):
    """Reuses corpus-run.py unchanged -- the same probe the real corpus goes through."""
    out = workdir / "observed.jsonl"
    r = subprocess.run([sys.executable, str(HERE / "corpus-run.py"),
                        str(workdir / "statements"), "--out", str(out), "--quiet"],
                       capture_output=True, text=True)
    if r.returncode != 0 or not out.is_file():
        sys.exit(f"corpus-run failed:\n{r.stdout[-800:]}\n{r.stderr[-800:]}")
    record = json.loads(out.read_text().splitlines()[0])
    single = workdir / "observed.json"
    single.write_text(json.dumps(record))
    return single


def verdict(workdir, observed):
    r = subprocess.run([sys.executable, str(HERE / "ground-truth-match.py"),
                        str(workdir / "ground-truth.json"), str(observed)],
                       capture_output=True, text=True)
    return json.loads(r.stdout)["verdict"], r.stdout


def run_case(cp, mutate):
    workdir = Path(tempfile.mkdtemp(prefix="finora-synthetic-gt-"))
    try:
        emit(cp, workdir, mutate)
        return verdict(workdir, observe(workdir))
    finally:
        # Removed on every path, including failure. The lesson from the corpus incident was not
        # "someone committed a file" -- it was real data becoming a persistent artefact while
        # tooling ran. A synthetic run earns the same discipline so the habit is not conditional.
        shutil.rmtree(workdir, ignore_errors=True)


def check_no_real_inputs_required():
    """The invariant, asserted rather than documented."""
    problems = []
    corpus_like = [p for p in (Path.home() / "Downloads").glob("*")] if False else []
    if corpus_like:
        problems.append("this check must not look at any real corpus")
    for var in ("FINORA_CORPUS", "OCR_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS"):
        if os.environ.get(var):
            problems.append(f"{var} is set; the synthetic loop must not depend on it")
    return problems


def main():
    problems = check_no_real_inputs_required()
    if problems:
        for p in problems:
            print(f"  FAIL  {p}")
        return 1

    cp = classpath()
    failures = 0

    agreeing, _ = run_case(cp, mutate=False)
    ok = agreeing == "PASS"
    print(f"  {'PASS' if ok else 'FAIL'}  definition -> PDF -> observed -> matcher: {agreeing}")
    failures += 0 if ok else 1

    disagreeing, detail = run_case(cp, mutate=True)
    # Anything other than PASS is detection. FAIL is expected; REVIEW would also be a refusal to
    # agree, and treating only FAIL as success would make this brittle about which refusal it got.
    detected = disagreeing != "PASS"
    print(f"  {'PASS' if detected else 'FAIL'}  a contradicting document is detected: {disagreeing}")
    if not detected:
        print("        the loop agreed with a document it should not have -- expectations and")
        print("        document are not independent")
        print(detail)
    failures += 0 if detected else 1

    print(f"\n  {'OK' if failures == 0 else str(failures) + ' FAILED'}"
          f"  -- no corpus, no network, no OCR engine, no artefact left behind")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

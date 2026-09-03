#!/usr/bin/env python3
"""Runs the real-statement corpus through the import pipeline and writes one JSON record per file.

WHY THIS EXISTS
---------------
A 16-statement sweep was run by hand once, and the findings from it (two statements extracting
nothing, two extracting a handful of rows from a dozen pages) are the reason this milestone exists.
Run by hand, it is evidence that expires: nobody can tell whether the next parser change improved
those numbers or quietly halved someone else's.

This makes the sweep repeatable and machine-readable. It is the "before" and the "after"; it does
NOT decide which is which -- comparing two runs is scripts/corpus-diff.py's job, deliberately kept
separate so that judging a regression and measuring the corpus cannot be conflated.

ONE RESPONSIBILITY
------------------
    corpus directory -> pipeline probe per statement -> normalised JSONL

WHAT IT DOES NOT DO
-------------------
No comparison, no pass/fail verdict, no exit code that depends on parser quality. A statement that
extracts zero rows is a successful RUN with a bad RESULT, and conflating those two would make this
script impossible to use as a baseline generator -- you cannot record a "before" with a tool that
refuses to finish when the before is bad.

OBSERVED FACTS vs DERIVED SIGNALS
---------------------------------
Each record separates `observed` from `derived`, and CorpusProbe (which produces the record) keeps
that split. Derived values are current opinions: the document classification has already been wrong
twice in this milestone -- once treating image density as text density, once treating positioned-run
count as text presence. Raw measurements have to survive independently so a wrong opinion can be
re-derived rather than re-measured.

Note what is deliberately NOT emitted: rows-per-page. `rows` and `pages` are both present, so a
consumer can form the ratio at full precision. An integer-divided field would invite comparing a
lossy number across runs, where HSBC's 1-row-from-4-pages and a regression from 12 rows to 3 are
both "0".

THE CORPUS LIVES OUTSIDE THE REPOSITORY, ALWAYS
-----------------------------------------------
These are real customer bank statements. They must never be committed -- see
scripts/check-fixture-hygiene.sh and the Synthetic Fixture Policy. This script therefore takes a
path OUTSIDE the working tree and refuses one inside it, the same guard trace-capture.sh applies for
the same reason. It follows that this script can never run in CI: CI has no access to the corpus.
CI gates on the committed trace corpus; this is a local pre-merge check.
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def _refuse_if_inside_repo(path: Path, what: str, allow_in_repo_synthetic_corpus: bool = False) -> None:
    """Real customer statements must never sit inside the working tree -- one `git add -A` away from
    being committed. The one exception is a committed, reviewed SYNTHETIC fixture corpus (Phase 2's
    mechanism-proof regression fixture and its successors), which is safe to commit by construction
    and needs an explicit, loudly-named opt-in to say so -- the refusal stays the unconditional
    default for everything else, including every real-corpus invocation this script has ever had.
    """
    if allow_in_repo_synthetic_corpus:
        return
    # is_relative_to, not a string prefix: a sibling directory that merely shares REPO_ROOT's name
    # as a prefix (e.g. a "finora-backup" next to "finora") is not inside it, and a naive
    # str.startswith check would wrongly refuse it -- this project's own worktree layout has
    # exactly that shape.
    if path.is_relative_to(REPO_ROOT):
        sys.exit(f"REFUSED: {path} is inside the repository.\n"
                 f"Real customer statements must live outside the working tree. Keep the {what} "
                 "elsewhere and pass an absolute path, or pass "
                 "--allow-in-repo-synthetic-corpus for a committed synthetic fixture corpus.")
BACKEND = REPO_ROOT / "backend"
PROBE_CLASS = "com.finora.imports.analysis.CorpusProbe"
CLASSPATH_CACHE = BACKEND / "target" / "corpus-classpath.txt"
SCHEMA = 1


def build_classpath(quiet: bool) -> str:
    """Test classes plus the dependency classpath, cached because resolving it costs ~20s.

    Cached under target/, which is gitignored and wiped by `mvn clean` -- so the cache cannot outlive
    a dependency change that would invalidate it.
    """
    test_classes = BACKEND / "target" / "test-classes"
    if not test_classes.is_dir():
        sys.exit("No target/test-classes. Run: cd backend && ./mvnw -o test-compile")

    if not CLASSPATH_CACHE.is_file():
        if not quiet:
            print("resolving dependency classpath (cached for subsequent runs)...", file=sys.stderr)
        result = subprocess.run(
            ["./mvnw", "-q", "-o", "dependency:build-classpath",
             f"-Dmdep.outputFile={CLASSPATH_CACHE}", "-Dmdep.includeScope=test"],
            cwd=BACKEND, capture_output=True, text=True)
        if not CLASSPATH_CACHE.is_file():
            sys.exit(f"could not resolve the classpath:\n{result.stderr[-800:]}")

    return f"{test_classes}:{BACKEND / 'target' / 'classes'}:{CLASSPATH_CACHE.read_text().strip()}"


def probe(classpath: str, pdf: Path, timeout: int, synthetic: bool = False, ocr: bool = False) -> dict:
    """One statement -> one record. Never raises; every failure becomes a record.

    A corpus run that dies on file 3 of 16 is not a corpus run, and the statement most likely to
    crash a parser is exactly the one worth recording. So a crash, a timeout and a non-JSON stdout
    all resolve to `status: "error"` with the reason attached, and the sweep continues.

    `synthetic` mirrors this script's own `--allow-in-repo-synthetic-corpus`: only a committed,
    reviewed fixture corpus ever sets it, and CorpusProbe's own default keeps every other call site
    on the safe, real-corpus path (see CorpusProbe.probe's doc comment).

    `ocr` routes CorpusProbe through the same RoutingTextAcquirer + TesseractRecogniser production
    uses, instead of the plain native-text-layer path this sweep has always run. Off by default: it
    needs `tesseract` on PATH and is much slower (real rasterization + OCR per page).
    """
    cmd = ["java", "-cp", classpath, PROBE_CLASS]
    if synthetic:
        cmd.append("--synthetic")
    if ocr:
        cmd.append("--ocr")
    cmd.append(str(pdf))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return _error(pdf, "Timeout", f"probe exceeded {timeout}s")
    except Exception as exc:                                    # noqa: BLE001 - must not propagate
        return _error(pdf, type(exc).__name__, str(exc))

    # The probe prints its record on stdout; PDFBox and logback print warnings there too, so the
    # record is found rather than assumed to be the whole of stdout.
    for line in result.stdout.splitlines():
        line = line.strip()
        if line.startswith("{") and '"schema"' in line:
            try:
                return json.loads(line)
            except json.JSONDecodeError as exc:
                return _error(pdf, "MalformedRecord", str(exc))

    detail = (result.stderr or result.stdout or "no output").strip().splitlines()
    return _error(pdf, "NoRecordEmitted",
                  detail[-1][:300] if detail else f"exit {result.returncode}")


def _error(pdf: Path, kind: str, message: str) -> dict:
    return {"schema": SCHEMA, "file": pdf.name, "status": "error",
            "error": {"type": kind, "message": message}}


def summarise(records: list[dict]) -> None:
    ok = [r for r in records if r.get("status") == "ok"]
    errors = [r for r in records if r.get("status") != "ok"]

    print(f"\n{'statement':<42} {'pages':>5} {'rows':>5}  {'classification':<30} suspect")
    print("-" * 96)
    for r in sorted(records, key=lambda x: x.get("file", "")):
        if r.get("status") != "ok":
            print(f"{r.get('file','?')[:41]:<42} {'-':>5} {'-':>5}  "
                  f"{'ERROR: ' + r['error']['type']:<30}")
            continue
        o, d = r["observed"], r["derived"]
        print(f"{r['file'][:41]:<42} {o['pages']:>5} {o['rows']:>5}  "
              f"{d['documentClassification']:<30} "
              f"{'yes' if d['suspectedIncompleteByPageRatio'] else ''}")
    print("-" * 96)

    by_class: dict[str, int] = {}
    for r in ok:
        c = r["derived"]["documentClassification"]
        by_class[c] = by_class.get(c, 0) + 1
    for cls, n in sorted(by_class.items(), key=lambda kv: -kv[1]):
        print(f"  {n:>3}  {cls}")
    if errors:
        print(f"  {len(errors):>3}  ERRORED")
    print(f"\n  inputs: {len(records)}   records: {len(records)}   "
          f"ok: {len(ok)}   errored: {len(errors)}")
    # Ground truth is a later step; until it exists no statement's expected count is known and every
    # classification is provisional. Printed every run so that stays visible rather than assumed.
    print(f"  expected transaction counts known: 0 / {len(records)}  (ground truth not yet established)")


def discover_pdfs(corpus: Path):
    """Every PDF in {corpus}, matching the extension case-insensitively.

    Real statements arrive named however the bank or the customer named them (e.g. an uppercase
    ".PDF"), and glob("*.pdf") is case-sensitive regardless of the underlying filesystem, so it
    silently drops those. Filtering by suffix instead catches every case.

    Shared in shape (and covered by one cross-checking test) with run-corpus-ground-truth.py's own
    discover_pdfs: the tool that MEASURES the corpus and the tool that JUDGES it must never disagree
    about which documents the corpus contains, because that disagreement is invisible in both
    outputs -- each simply reports on the set it saw.
    
    Recursive (rglob), not a flat listing. The corpus is a human-maintained directory that people
    reorganise -- it has already been split into per-product subdirectories once -- and a flat
    iterdir() sees nothing at all after such a move. That failure is loud (both tools exit with
    "no .pdf files") rather than silent, but it still leaves the correctness gate covering zero
    documents until someone notices, which is the same class of gap as the case-sensitive glob
    this function replaced. Sorting by name first, then full path, keeps the report ordered the way
    a reader expects regardless of which subdirectory a document happens to live in.
"""
    return sorted((p for p in corpus.rglob("*") if p.is_file() and p.suffix.lower() == ".pdf"),
                  key=lambda p: (p.name, str(p)))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus", type=Path, help="directory of real statements, OUTSIDE this repository")
    ap.add_argument("-o", "--out", type=Path, required=True, help="JSONL output path")
    ap.add_argument("--timeout", type=int, default=180, help="per-statement timeout in seconds")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--allow-in-repo-synthetic-corpus", action="store_true",
                     help="skip the in-repo refusal for a committed, reviewed SYNTHETIC fixture "
                          "corpus. Never pass this for a real-statement corpus.")
    ap.add_argument("--ocr", action="store_true",
                     help="route acquisition through RoutingTextAcquirer + TesseractRecogniser, the "
                          "same OCR fallback production uses, instead of the native text-layer-only "
                          "path this sweep otherwise runs. Requires tesseract on PATH; much slower.")
    args = ap.parse_args()

    corpus = args.corpus.resolve()
    if not corpus.is_dir():
        sys.exit(f"not a directory: {corpus}")

    _refuse_if_inside_repo(corpus, "corpus", args.allow_in_repo_synthetic_corpus)

    pdfs = discover_pdfs(corpus)
    if not pdfs:
        sys.exit(f"no .pdf files in {corpus}")

    classpath = build_classpath(args.quiet)

    records = []
    for i, pdf in enumerate(pdfs, 1):
        if not args.quiet:
            print(f"  {i:>2}/{len(pdfs)} {pdf.name}", file=sys.stderr)
        records.append(probe(classpath, pdf, args.timeout, args.allow_in_repo_synthetic_corpus, args.ocr))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as fh:
        for r in records:
            fh.write(json.dumps(r, sort_keys=True) + "\n")

    if not args.quiet:
        summarise(records)
        print(f"\n  written -> {args.out}")

    # Exit 0 whenever the RUN completed, regardless of what it found. A bad corpus result is data,
    # not a script failure -- and a baseline generator that refuses to write a bad baseline is
    # useless for recording where you actually are.
    return 0


if __name__ == "__main__":
    sys.exit(main())

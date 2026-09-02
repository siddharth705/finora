#!/usr/bin/env python3
"""Runs the real corpus through CorpusProbe and matches each document against its ground truth.

WHY THIS EXISTS
---------------
scripts/corpus-run.py measures where the pipeline is. scripts/ground-truth-match.py judges one
observed record against one expected document. Nothing yet composes them across a whole corpus in
one command, so establishing ground truth for a document and then checking it stayed at least as
correct after a parser change was a manual, one-document-at-a-time exercise. This closes that gap
by composing the two existing tools -- it does not reimplement either.

ONE RESPONSIBILITY
-------------------
    corpus directory + ground-truth directory -> per-document probe -> match against ground truth,
    where established -> pass/fail summary

WHAT IT DOES NOT DO
--------------------
No new matching logic -- every verdict comes from ground-truth-match.py's match(), invoked exactly
as scripts/test-synthetic-ground-truth.py already does (subprocess, not import, so this script and
the reference implementation can never drift into two authorities that disagree about the same
document -- see GroundTruthDocument.java's own doc comment for why that split is deliberate).

A document with no ground-truth file is not a failure -- it is "not yet established", the same
distinction corpus-run.py already prints on every run ("expected transaction counts known: 0 / N").
Establishing ground truth for the rest of the corpus is expected to happen incrementally, one file
at a time, with no change needed here when it does.

THE CORPUS AND ITS GROUND TRUTH LIVE OUTSIDE THE REPOSITORY, ALWAYS
---------------------------------------------------------------------
Same refusal as corpus-run.py, for the same reason (see its own docstring and the 2026-08-08
incident in docs/investigations/incidents/): real customer statements, and anything derived from
them -- including expected transaction counts and product types -- must never be committed. Ground
truth for a real document is data of the same sensitivity as the document itself
(ground-truth-model-design.md §2). Both the corpus directory and the ground-truth directory are
checked and refused if either sits inside the repository.
"""

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BACKEND = REPO_ROOT / "backend"
PROBE_CLASS = "com.finora.imports.analysis.CorpusProbe"
MATCHER = Path(__file__).resolve().parent / "ground-truth-match.py"
CLASSPATH_CACHE = BACKEND / "target" / "corpus-classpath.txt"

NOT_ESTABLISHED = "NOT_ESTABLISHED"


def _refuse_if_inside_repo(path: Path, what: str, allow_in_repo_synthetic_corpus: bool = False) -> None:
    """Same guard corpus-run.py already applies, reused verbatim for a second path.

    A ground-truth file is exactly as sensitive as the corpus it describes (expected transaction
    counts and product types are still customer financial facts), so it gets the identical refusal,
    not a weaker one. The one exception, same as corpus-run.py's: a committed, reviewed SYNTHETIC
    fixture corpus and its ground truth, opted into explicitly.
    """
    if allow_in_repo_synthetic_corpus:
        return
    # is_relative_to, not a string prefix -- see corpus-run.py's identical guard for why a naive
    # str.startswith check wrongly refuses a sibling directory that merely shares REPO_ROOT's name
    # as a prefix.
    if path.is_relative_to(REPO_ROOT):
        sys.exit(f"REFUSED: {path} is inside the repository.\n"
                 f"The {what} must live outside the working tree. Keep it elsewhere and pass an "
                 "absolute path, or pass --allow-in-repo-synthetic-corpus for a committed synthetic "
                 "fixture corpus.")


def build_classpath(quiet: bool) -> str:
    test_classes = BACKEND / "target" / "test-classes"
    if not test_classes.is_dir():
        sys.exit("No target/test-classes. Run: cd backend && ./mvnw -o test-compile")

    if not CLASSPATH_CACHE.is_file():
        if not quiet:
            print("resolving dependency classpath (cached for subsequent runs)...", file=sys.stderr)
        subprocess.run(
            ["./mvnw", "-q", "-o", "dependency:build-classpath",
             f"-Dmdep.outputFile={CLASSPATH_CACHE}", "-Dmdep.includeScope=test"],
            cwd=BACKEND, capture_output=True, text=True)
        if not CLASSPATH_CACHE.is_file():
            sys.exit("could not resolve the classpath")

    return f"{test_classes}:{BACKEND / 'target' / 'classes'}:{CLASSPATH_CACHE.read_text().strip()}"


def probe(classpath: str, pdf: Path, timeout: int, synthetic: bool = False) -> dict:
    """One statement -> one CorpusProbe record. Identical invocation to corpus-run.py's probe().

    `synthetic` threads through to CorpusProbe's own --synthetic flag -- only ever set when this
    script's --allow-in-repo-synthetic-corpus was passed, which is the only situation where
    per-row transaction content (the "description" dimension VALUE_DIMENSIONS now compares) is
    safe to reveal at all.
    """
    cmd = ["java", "-cp", classpath, PROBE_CLASS]
    if synthetic:
        cmd.append("--synthetic")
    cmd.append(str(pdf))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"schema": 1, "file": pdf.name, "status": "error",
                "error": {"type": "Timeout", "message": f"probe exceeded {timeout}s"}}
    except Exception as exc:                                    # noqa: BLE001 - must not propagate
        return {"schema": 1, "file": pdf.name, "status": "error",
                "error": {"type": type(exc).__name__, "message": str(exc)}}

    for line in result.stdout.splitlines():
        line = line.strip()
        if line.startswith("{") and '"schema"' in line:
            try:
                return json.loads(line)
            except json.JSONDecodeError as exc:
                return {"schema": 1, "file": pdf.name, "status": "error",
                        "error": {"type": "MalformedRecord", "message": str(exc)}}

    detail = (result.stderr or result.stdout or "no output").strip().splitlines()
    return {"schema": 1, "file": pdf.name, "status": "error",
            "error": {"type": "NoRecordEmitted",
                      "message": detail[-1][:300] if detail else f"exit {result.returncode}"}}


def match_against_ground_truth(ground_truth_path: Path, record: dict, workdir: Path) -> dict:
    """Invokes ground-truth-match.py exactly as test-synthetic-ground-truth.py already does --
    subprocess, not import, so this composition can never disagree with the reference
    implementation's own tests about what a verdict means."""
    observed = workdir / "observed.json"
    observed.write_text(json.dumps(record))
    result = subprocess.run(
        [sys.executable, str(MATCHER), str(ground_truth_path), str(observed)],
        capture_output=True, text=True)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {"verdict": "ERROR", "entities": [],
                "detail": (result.stderr or result.stdout or "no output")[-500:]}


def discover_pdfs(corpus: Path):
    """Every PDF in {corpus}, matching the extension case-insensitively.

    Deliberately not glob("*.pdf"). At least one real statement in the corpus is named with an
    uppercase extension ("SBI Credit Card.PDF"), and Path.glob is case-sensitive regardless of
    whether the underlying filesystem is -- so globbing silently dropped that document from every
    run of this script. A correctness gate that SKIPS a file it was asked to judge is worse than one
    that fails on it: the run still summarises as a clean pass, so the gap is invisible in exactly
    the output someone would check. (That document turned out to have no ground-truth file either,
    which is precisely the sort of thing this script exists to surface and could not.)

    scripts/corpus-run.py already carries this same fix and its own comment explaining it; this is
    that fix, ported, so the measuring tool and the judging tool can never disagree about which
    documents the corpus contains.
    """
    return sorted((p for p in corpus.iterdir() if p.is_file() and p.suffix.lower() == ".pdf"),
                  key=lambda p: p.name)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus", type=Path, help="directory of real statements, OUTSIDE this repository")
    ap.add_argument("--ground-truth", type=Path, default=None,
                     help="directory of <filename>.json ground-truth files, OUTSIDE this repository "
                          "(default: <corpus>/ground-truth)")
    ap.add_argument("--timeout", type=int, default=180, help="per-statement probe timeout in seconds")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--allow-in-repo-synthetic-corpus", action="store_true",
                     help="skip the in-repo refusal for a committed, reviewed SYNTHETIC fixture "
                          "corpus, and probe with CorpusProbe's --synthetic flag. Never pass this "
                          "for a real-statement corpus.")
    args = ap.parse_args()

    corpus = args.corpus.resolve()
    if not corpus.is_dir():
        sys.exit(f"not a directory: {corpus}")
    _refuse_if_inside_repo(corpus, "corpus", args.allow_in_repo_synthetic_corpus)

    ground_truth_dir = (args.ground_truth or (corpus / "ground-truth")).resolve()
    _refuse_if_inside_repo(ground_truth_dir, "ground-truth directory", args.allow_in_repo_synthetic_corpus)

    pdfs = discover_pdfs(corpus)
    if not pdfs:
        sys.exit(f"no .pdf files in {corpus}")

    classpath = build_classpath(args.quiet)

    rows = []
    any_fail = False
    with tempfile.TemporaryDirectory(prefix="finora-ground-truth-") as tmp:
        workdir = Path(tmp)
        for i, pdf in enumerate(pdfs, 1):
            if not args.quiet:
                print(f"  {i:>2}/{len(pdfs)} {pdf.name}", file=sys.stderr)
            record = probe(classpath, pdf, args.timeout, args.allow_in_repo_synthetic_corpus)

            gt_file = ground_truth_dir / (pdf.stem + ".json")
            if not gt_file.is_file():
                rows.append((pdf.name, NOT_ESTABLISHED, "no ground-truth file for this document"))
                continue
            if record.get("status") != "ok":
                rows.append((pdf.name, "ERROR",
                              f"probe failed: {record.get('error', {}).get('type', '?')}"))
                any_fail = True
                continue

            verdict = match_against_ground_truth(gt_file, record, workdir)
            issues = [
                f"{e['id']}: {e.get('detail', e['outcome'])}"
                for e in verdict.get("entities", [])
                if e.get("outcome") != "MATCHED" or e.get("status") not in (None, "PASS")
            ]
            # Unexpected sections worsen the verdict to REVIEW (match_against_ground_truth's own
            # logic) but live in a SEPARATE list from entities -- omitting them here was a real bug:
            # a document could show verdict=REVIEW with a detail column claiming "as expected"
            # because only the entities list was ever read. Found running this against the real
            # corpus, not by a test.
            issues += [
                f"section {u['section']}: {u.get('detail', u['outcome'])}"
                for u in verdict.get("unexpected", [])
            ]
            detail = "; ".join(issues) or "as expected"
            rows.append((pdf.name, verdict.get("verdict", "ERROR"), detail))
            if verdict.get("verdict") == "FAIL":
                any_fail = True

    if not args.quiet:
        print(f"\n{'statement':<42} {'verdict':<16} detail")
        print("-" * 100)
        for name, verdict, detail in rows:
            print(f"{name[:41]:<42} {verdict:<16} {detail}")
        print("-" * 100)
        established = [r for r in rows if r[1] != NOT_ESTABLISHED]
        passed = [r for r in established if r[1] == "PASS"]
        print(f"\n  ground truth established: {len(established)} / {len(rows)}   "
              f"passed: {len(passed)} / {len(established)}")

    return 1 if any_fail else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Publishes the backend suite's real numbers to the CI run page, and fails if they are hollow.

WHY THIS EXISTS
---------------
`./mvnw test` exiting 0 is a weaker claim than it looks. Two things this repository has actually
hit are both invisible in that exit code:

1. The suite not running at all. backend/mvnw was committed non-executable, so every CI run from
   the workflow's creation until 0e92d83 died at `./mvnw: Permission denied` -- and the only place
   that showed up was inside a collapsed log step, so main sat red for days without anyone able to
   see that the red meant "no backend test has ever run".
2. A subset silently not matching. The 29 *IT classes never executed for months because surefire's
   default includes do not match *IT.java (that is failsafe's convention). The suite was green at
   941 tests the whole time; widening the includes took it to 1055. Nothing failed while a third
   of the controller layer went untested -- coverage for com.finora.controller was 0.0%.

Both share a shape: the signal that something was missing was a *smaller number*, and no one was
looking at the number. So this does two things -- renders the counts where they cannot be missed
(GitHub's run summary, not a log line), and treats a hollow result as a failure rather than
letting "0 tests, 0 failures" render as success.

WHAT MAKES IT FAIL
------------------
- No surefire reports at all: the test step did not produce results, whatever its exit code.
- Zero tests parsed: green because nothing ran, the exact shape of incident 1.
- Zero *IT classes: the ITs stopped matching again, the exact shape of incident 2. This is
  deliberately a hard failure and not a warning -- it was a warning-shaped problem last time, and
  it went unnoticed for months. If the ITs are ever intentionally removed, this assertion should
  be deleted in the same commit, which is the point: it forces the decision to be explicit.

  --unit-only waives ONLY this last check, for the one legitimate case where zero *IT classes is
  correct rather than a regression: ci.yml's PR path runs `./mvnw test` (surefire, unit-only by
  design -- see backend/pom.xml's 2026-09-02 comment on the surefire/failsafe split), where *IT
  never runs at all. The push-to-main path runs `./mvnw verify` and calls this script WITHOUT the
  flag, so the check still fires, by default, on the one run where *IT silently not executing
  would actually be incident 2 again.

It does NOT fail on test failures themselves -- the test step already did that, and this runs with
`if: !cancelled()` so the summary still renders for a failing run, which is when the breakdown is
most useful.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = REPO_ROOT / "backend" / "target" / "surefire-reports"

# Matches the pom's surefire <includes>. Kept as a suffix check rather than a regex because that
# is precisely what surefire's **/*IT.java means for the class's simple name.
IT_SUFFIX = "IT"


def parse():
    suites = []
    for path in sorted(REPORT_DIR.glob("TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"warning: could not parse {path.name}: {exc}", file=sys.stderr)
            continue
        if root.tag != "testsuite":
            continue
        suites.append({
            "name": root.get("name", path.stem),
            "tests": int(root.get("tests", 0)),
            "failures": int(root.get("failures", 0)),
            "errors": int(root.get("errors", 0)),
            "skipped": int(root.get("skipped", 0)),
            "time": float(root.get("time", 0) or 0),
        })
    return suites


def totals(suites):
    return {
        k: sum(s[k] for s in suites)
        for k in ("tests", "failures", "errors", "skipped")
    } | {"time": sum(s["time"] for s in suites), "classes": len(suites)}


def render(suites):
    integration = [s for s in suites if s["name"].rsplit(".", 1)[-1].endswith(IT_SUFFIX)]
    unit = [s for s in suites if s not in integration]
    t, ti, tu = totals(suites), totals(integration), totals(unit)

    ok = t["failures"] == 0 and t["errors"] == 0
    lines = [
        # ASCII only: this also prints to stdout, and a Windows console under cp1252 turns a
        # non-representable character into a UnicodeEncodeError -- a summary script that crashes
        # while reporting a passing suite would be its own small version of the problem above.
        f"## Backend suite - {'passed' if ok else 'FAILED'}",
        "",
        "| | Classes | Tests | Failures | Errors | Skipped | Time |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for label, x in (("Unit", tu), ("Integration (`*IT`)", ti), ("**Total**", t)):
        lines.append(
            f"| {label} | {x['classes']} | {x['tests']} | {x['failures']} | "
            f"{x['errors']} | {x['skipped']} | {x['time']:.1f}s |"
        )

    failing = [s for s in suites if s["failures"] or s["errors"]]
    if failing:
        lines += ["", "### Failing classes", ""]
        for s in sorted(failing, key=lambda s: -(s["failures"] + s["errors"])):
            lines.append(f"- `{s['name']}`: {s['failures']} failed, {s['errors']} errored")

    return "\n".join(lines) + "\n", t, ti


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--unit-only", action="store_true",
        help="Waive the zero-*IT-classes check. Only correct for a run that never invokes "
             "failsafe on purpose (ci.yml's PR path) -- see this module's docstring.")
    args = parser.parse_args()

    if not REPORT_DIR.is_dir():
        print(f"BLOCKED: no surefire reports at {REPORT_DIR.relative_to(REPO_ROOT)}.")
        print("The test step produced no results, whatever exit code it returned.")
        return 1

    suites = parse()
    if not suites:
        print("BLOCKED: surefire report directory exists but contains no parseable testsuite XML.")
        return 1

    summary, t, ti = render(suites)

    # GITHUB_STEP_SUMMARY renders on the run page itself. Falling back to stdout keeps the script
    # runnable locally, where it is the fastest way to see the unit/IT split after a run.
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary)
    print(summary)

    if t["tests"] == 0:
        print("BLOCKED: 0 tests ran. A suite that executes nothing is not a passing suite.")
        return 1

    if ti["classes"] == 0 and not args.unit_only:
        print(
            "BLOCKED: no *IT classes ran. Either failsafe's includes stopped matching **/*IT.java\n"
            "(see backend/pom.xml), or this run should have passed --unit-only and did not. This\n"
            "previously hid 114 tests and left com.finora.controller at 0% coverage while the\n"
            "suite still reported green."
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())

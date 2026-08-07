#!/usr/bin/env python3
"""Fails when a client asserts a month over a dashboard figure instead of rendering the one it was
given.

WHY THIS EXISTS
---------------
Bug 05. `DashboardService` reports on the newest month the account has DATA for, which for a
product built around importing statements in arrears is routinely not the current calendar month.
That choice is correct -- an empty "this month" is a worse answer than last month's real figures --
but the response did not say WHICH month, so both clients labelled the figures "this month" and
"vs last month". A user who had not yet imported August read July's income, expenses, savings rate
and category breakdown as August's.

The server-side half was fixed by adding `reportingMonth` / `reportingMonthIsCurrent` to
`DashboardSummaryDto`. That is exactly the kind of fix that lands in one client and not the other:
the web dashboard was updated in the same change, and mobile's `DashboardScreen.tsx` kept three
hardcoded claims -- a visible label, an accessibility label, and an empty state -- for a while
afterwards. This is the same failure shape `check-client-auth-policy.py` was written for ("a
401-handling fix landed on web, missed mobile"), applied to the reporting layer.

A wrong label here is not cosmetic. The figure and the period are a single claim; getting the
period wrong makes the number wrong, and it is wrong silently, because nothing about a stale month
looks different from a current one.

WHAT IT CHECKS
--------------
For each client that renders the dashboard summary:

1. It reads `reportingMonth` and `reportingMonthIsCurrent` at all. A client that never looks at
   them cannot be rendering the right period, whatever its labels say.
2. It contains no hardcoded month assertion ("this month", "vs last month", "last month") in the
   dashboard screen. Those must be derived from the fields above.

Both checks are needed. Check 2 alone passes a client that deleted its labels entirely; check 1
alone passes a client that reads the fields and ignores them.

WHY A CHECK RATHER THAN SHARED CODE
-----------------------------------
Same answer `check-client-auth-policy.py` records at length, for the same three independently built
and deployed apps: sharing a dozen lines of label logic across them costs Metro `watchFolders`,
Vite/Vitest aliases and TypeScript path mappings in three projects. The duplication is not the
problem; the duplication being unenforced is. So this enforces it.

ACCEPTING A FINDING
-------------------
There is deliberately no accept-list. A dashboard label that genuinely means the calendar month --
a budget figure, say -- belongs in a file this script does not scan, because budget figures are
computed from the calendar month server-side (see ReportingPeriod's feature mapping). If you need
an exception, that is a signal the feature is on the wrong side of that mapping.

USAGE
-----
    python3 scripts/check-reporting-period-labels.py

Paths are relative to this script, not the working directory.
"""
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.join(SCRIPT_DIR, "..")

# The screen that renders the dashboard summary, per client. Mobile and web only: the admin portal
# has its own dashboard fed by admin analytics, not by DashboardSummaryDto.
DASHBOARD_SCREENS = [
    "frontend/src/pages/Dashboard.tsx",
    "mobile/src/screens/DashboardScreen.tsx",
]

REQUIRED_FIELDS = ("reportingMonth", "reportingMonthIsCurrent")

# Phrases that assert a period. These are NOT banned outright -- "this month" is the correct text
# when the reporting month IS the current one, and both clients legitimately use it as the guarded
# branch of a ternary. What is banned is asserting one UNCONDITIONALLY.
#
# The first version of this script banned the literals outright and failed on the correct code it
# was written to protect. That is the same mistake this repository has now recorded twice --
# check-imports.py "checked a proxy for the property it actually cared about", and
# scaling-triggers.md's "a count is a hypothesis, not a measurement". The property here is not
# "does this string appear"; it is "is this string conditional on the period the server reported".
PERIOD_CLAIMS = ("this month", "vs last month", "versus last month")

# Identifiers that mean the surrounding expression is derived from the server's period. A claim
# within GUARD_WINDOW lines of one of these is guarded; anything else is a bare assertion.
PERIOD_GUARDS = ("reportingMonth", "reportingMonthIsCurrent", "periodIsCurrent",
                 "periodLabel", "deltaLabel", "deltaSpokenLabel")

# A guarded claim sits inside a ternary that spans a few lines at most:
#     const periodLabel = periodIsCurrent
#       ? 'this month'
#       : monthLabel(summary.reportingMonth!);
# Three lines either side covers that shape with room to spare, without being so wide that an
# unrelated bare assertion elsewhere in the file gets excused by a distant guard.
GUARD_WINDOW = 3


def strip_comments_and_jsx_comments(text: str) -> str:
    """Blank out // line comments, /* */ blocks, and {/* */} JSX comments.

    Without this the check fires on its own documentation: both dashboard files explain the bug in
    prose that necessarily contains the banned phrases.
    """
    text = re.sub(r"\{/\*.*?\*/\}", " ", text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"^\s*//[^\n]*", " ", text, flags=re.M)
    text = re.sub(r"(?<=[;)}\s])//[^\n]*", " ", text)
    return text


def check(path: str) -> list:
    full = os.path.join(REPO_ROOT, path)
    if not os.path.exists(full):
        return [f"{path}: expected to exist -- if this screen moved, update this script"]

    with open(full, encoding="utf-8") as fh:
        raw = fh.read()

    problems = []

    for field in REQUIRED_FIELDS:
        if field not in raw:
            problems.append(
                f"{path}: never reads `{field}`. It cannot be rendering the right period, "
                f"whatever its labels say.")

    lines = strip_comments_and_jsx_comments(raw).split("\n")
    lowered = [ln.lower() for ln in lines]

    for i, line in enumerate(lowered):
        for claim in PERIOD_CLAIMS:
            if claim not in line:
                continue
            window = lines[max(0, i - GUARD_WINDOW):i + GUARD_WINDOW + 1]
            if any(guard in "\n".join(window) for guard in PERIOD_GUARDS):
                continue  # conditional on the reported period -- this is the correct shape
            problems.append(
                f"{path}:{i + 1}: asserts \"{claim}\" unconditionally. The dashboard's figures are "
                f"the newest month with DATA, which is routinely not the current one -- make this "
                f"label conditional on reportingMonthIsCurrent.")

    return problems


def main() -> int:
    problems = []
    for path in DASHBOARD_SCREENS:
        problems.extend(check(path))

    if not problems:
        print(f"check-reporting-period-labels: {len(DASHBOARD_SCREENS)} dashboard screen(s) clean "
              f"-- every client renders the period the server reported.")
        return 0

    print("REPORTING PERIOD LABEL DRIFT", file=sys.stderr)
    for p in problems:
        print(f"  {p}", file=sys.stderr)
    print("\nThe figure and the period are one claim. A client that names the wrong month makes "
          "the number wrong,\nand does it silently -- a stale month looks exactly like a current "
          "one. See ReportingPeriod's\nfeature mapping for which month each feature must use.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

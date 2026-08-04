#!/usr/bin/env python3
"""Fails when a JS app has a shipped-code advisory nobody has explicitly accepted.

WHY THIS EXISTS
---------------
`npm audit` in this repo reports 18 vulnerability entries across the three JS apps, and every one
of them is already known and already judged not to apply. That is the problem. A command whose
output is always non-empty is a command everyone learns to skip, and the next real advisory arrives
into a stream people have been trained to ignore. Item 4 of the repo improvement proposal is the
worked example: a genuine open-redirect in react-router sat in that noise.

So the point of this script is not to find vulnerabilities -- `npm audit` already does that. It is
to make the output mean something: every advisory is either **allowlisted here, with a reason and a
name for what would change the answer**, or it fails the build. There is no third state where an
advisory is merely tolerated.

WHAT IT CHECKS
--------------
`npm audit --omit=dev`, i.e. what actually ships, per app. Dev-only advisories are reported but do
not fail: they are build and deploy tooling, not code a user runs. The most numerous ones today
(five `undici` highs) reach the tree through @cloudflare/vite-plugin -> miniflare, which exists to
emulate Cloudflare locally.

Note `--omit=dev` is NOT the same as "does not run on a user's device" for the mobile app: the Expo
CLI toolchain is a transitive dependency of the `expo` package itself, which is a production
dependency, so build-time tooling appears in a production audit. The allowlist says so explicitly
rather than leaving the next reader to work it out.
"""

import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
APPS = ["frontend", "admin-portal", "mobile"]


class Accepted:
    """One advisory somebody has looked at and decided not to act on."""

    def __init__(self, ghsa, apps, summary, why, revisit):
        self.ghsa = ghsa
        self.apps = apps
        self.summary = summary
        self.why = why
        self.revisit = revisit


# Every entry needs a reason that could turn out to be WRONG, and a named trigger for re-checking.
# "Low risk" is not a reason; "requires a feature this app does not use" is.
ACCEPTED = [
    Accepted(
        ghsa="GHSA-qwww-vcr4-c8h2",
        apps={"frontend", "admin-portal"},
        summary="react-router: RSC Mode CSRF bypass",
        why=(
            "Requires React Server Components. Both apps are client-rendered SPAs mounted with\n"
            "      BrowserRouter and have no server runtime, so the affected code path does not exist\n"
            "      in either build. There is also no version that clears it: the advisory's fix\n"
            "      boundary is 8.3.0 and no 8.x has been published. npm's suggested remediation\n"
            "      (downgrade to 7.11.0) would REINTRODUCE the open redirect fixed in 1ea5d13, which\n"
            "      does apply -- so taking npm's advice here would make the apps less safe, not more."
        ),
        revisit="When react-router 8.3.0 or later is published, upgrade and delete this entry.",
    ),
    Accepted(
        ghsa="GHSA-w5hq-g745-h8pq",
        apps={"mobile"},
        summary="uuid: missing buffer bounds check in v3/v5/v6 when buf is provided",
        why=(
            "Reached only as expo -> @expo/cli -> xcode -> uuid. That is the build and CLI\n"
            "      toolchain; it runs on a developer machine and on the EAS builder, never on a\n"
            "      user's device. It shows up in a production audit only because @expo/cli is a\n"
            "      transitive dependency of the `expo` package, which is itself a production\n"
            "      dependency -- a packaging artefact, not a statement about what ships.\n"
            "      An npm override was considered and rejected: forcing a transitive version inside\n"
            "      the Expo toolchain risks breaking `expo export`, which CI depends on, to fix\n"
            "      something that never executes on a device."
        ),
        revisit="When Expo ships a toolchain release that resolves it upstream.",
    ),
]

ACCEPTED_BY_GHSA = {a.ghsa: a for a in ACCEPTED}


def audit(app, omit_dev):
    """npm audit --json. Exit code is non-zero whenever anything is found, so it is ignored; the
    JSON body is the actual result."""
    cmd = ["npm", "audit", "--json"]
    if omit_dev:
        cmd.append("--omit=dev")
    proc = subprocess.run(
        cmd, cwd=REPO_ROOT / app, capture_output=True, text=True, shell=(sys.platform == "win32")
    )
    if not proc.stdout.strip():
        raise RuntimeError(f"npm audit produced no output for {app}: {proc.stderr[:300]}")
    return json.loads(proc.stdout)


def advisories(report):
    """GHSA id -> (severity, title). npm nests the same advisory under every affected package, so
    this collapses them; 14 reported 'vulnerabilities' in mobile are one advisory."""
    found = {}
    for entry in (report.get("vulnerabilities") or {}).values():
        for via in entry.get("via") or []:
            if isinstance(via, dict) and via.get("url", "").startswith("https://github.com/advisories/"):
                found[via["url"].rsplit("/", 1)[-1]] = (entry.get("severity"), via.get("title", ""))
    return found


def main():
    apps = sys.argv[1:] or APPS
    unexpected = []
    stale = []

    for app in apps:
        if not (REPO_ROOT / app / "package.json").exists():
            print(f"  {app}: no package.json, skipping")
            continue

        shipped = advisories(audit(app, omit_dev=True))
        dev_total = audit(app, omit_dev=False).get("metadata", {}).get("vulnerabilities", {}).get("total", 0)

        print(f"\n{app}: {len(shipped)} distinct advisory(ies) in shipped code "
              f"({dev_total} total entries including dev tooling)")

        for ghsa, (severity, title) in sorted(shipped.items()):
            accepted = ACCEPTED_BY_GHSA.get(ghsa)
            if accepted and app in accepted.apps:
                print(f"  accepted  {severity:<8} {ghsa}  {title[:60]}")
            else:
                unexpected.append((app, ghsa, severity, title))
                print(f"  NEW       {severity:<8} {ghsa}  {title[:60]}")

        for accepted in ACCEPTED:
            if app in accepted.apps and accepted.ghsa not in shipped:
                stale.append((app, accepted))

    for app, accepted in stale:
        print(f"\nSTALE ALLOWLIST ENTRY: {accepted.ghsa} is no longer reported for {app}.")
        print(f"  Remove it from {Path(__file__).name} -- a stale exception hides the next real one.")

    if unexpected:
        print("\nBLOCKED: advisories in shipped code that nobody has accepted:\n", file=sys.stderr)
        for app, ghsa, severity, title in unexpected:
            print(f"  {app}: {severity} {ghsa} — {title}", file=sys.stderr)
        print(
            "\n  Decide, then record the decision. Either upgrade the dependency, or add an entry to\n"
            "  ACCEPTED in this script with a reason that could be wrong and a named trigger for\n"
            "  re-checking it. Do not simply raise the threshold.",
            file=sys.stderr,
        )
        return 1

    if stale:
        return 1

    print("\nClean -- every shipped-code advisory is explicitly accounted for:")
    for accepted in ACCEPTED:
        print(f"\n  {accepted.ghsa}  ({', '.join(sorted(accepted.apps))})  {accepted.summary}")
        print(f"      {accepted.why}")
        print(f"      Revisit: {accepted.revisit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

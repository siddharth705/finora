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
    # GHSA-qwww-vcr4-c8h2 (react-router: RSC Mode CSRF bypass) was here for frontend and
    # admin-portal, and was removed on 2026-08-08 because npm audit stopped reporting it for either.
    #
    # Worth recording that it did NOT go away for the reason the entry predicted. Its revisit
    # trigger was "when react-router 8.3.0 or later is published, upgrade and delete this entry",
    # and that has not happened: both apps are still on 7.18.2. The advisory was withdrawn or its
    # affected range amended upstream, which is a thing that happens and which this checker
    # deliberately notices -- it failed the build the first time the entry went stale rather than
    # letting a dead exception sit here shadowing the next real advisory in react-router.
    #
    # The reasoning is not lost if it comes back: it is in this file's history, and the finding
    # that npm's suggested remediation (downgrade to 7.11.0) would reintroduce the open redirect
    # fixed in 1ea5d13 is the part worth re-reading before anyone acts on that advice again.
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
    # The two image-size entries below are one finding in two parsers of the same package, reached
    # by the same single path, so they are recorded together and should be removed together.
    #
    # There is no upgrade to take, which is the part worth knowing before anyone re-litigates this:
    # 1.2.1 is the LAST 1.x release -- the fix exists only in 2.x -- and metro declares
    # `image-size@^1.0.2`, so no in-range version resolves to a fixed one. An npm override to 2.x
    # was considered and rejected for the reason already recorded on the uuid entry above: forcing
    # a major version into the Expo toolchain risks breaking `expo export`, which CI depends on, to
    # fix something that never runs on a device.
    Accepted(
        ghsa="GHSA-5p2g-fcmc-qvqq",
        apps={"mobile"},
        summary="image-size: JXL and HEIF parsers allow denial of service through infinite loops",
        why=(
            "Reached only as expo -> metro -> image-size. metro is the React Native bundler: it\n"
            "      reads asset dimensions at BUILD time, on a developer machine or the EAS builder,\n"
            "      and is not part of the shipped bundle. metro is not a dependency of this app at\n"
            "      all, neither direct nor dev -- it appears in a production audit solely because\n"
            "      `expo` is a production dependency that carries the toolchain with it, the same\n"
            "      packaging artefact this file's own header describes.\n"
            "      Reaching the loop needs a malformed JXL or HEIF image inside this repo's asset\n"
            "      tree, which presumes commit access; the worst outcome is a hung build, not\n"
            "      anything on a user's device.\n"
            "      THIS IS WRONG the moment image-size becomes reachable from runtime code. The\n"
            "      check is `npm ls image-size` in mobile/: metro must still be the only dependent."
        ),
        revisit=(
            "After any Expo SDK upgrade, re-check whether metro has moved to image-size 2.x, "
            "and delete both image-size entries when it has."
        ),
    ),
    Accepted(
        ghsa="GHSA-w3rx-r6r6-pgpr",
        apps={"mobile"},
        summary="image-size: ICNS parser allows denial of service through an infinite loop",
        why=(
            "Same package, same single path, same build-time-only exposure as GHSA-5p2g-fcmc-qvqq\n"
            "      above -- expo -> metro -> image-size, where metro is the bundler and never ships.\n"
            "      Recorded separately because this checker keys on GHSA id, so a shared entry would\n"
            "      leave the other advisory silently unaccounted for.\n"
            "      THIS IS WRONG under the same condition: if image-size ever becomes reachable from\n"
            "      runtime code. Verify with `npm ls image-size` in mobile/."
        ),
        revisit=(
            "After any Expo SDK upgrade, re-check whether metro has moved to image-size 2.x, "
            "and delete both image-size entries when it has."
        ),
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

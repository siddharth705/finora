#!/usr/bin/env python3
"""Fails when something CI or a git hook runs as `./path` is not executable in the index.

WHY THIS EXISTS
---------------
backend/mvnw was committed as mode 100644 in the initial commit and never fixed. Every CI run
since the workflow was added died on its first real step:

    Run ./mvnw -B --no-transfer-progress test
    /home/runner/work/_temp/....sh: line 1: ./mvnw: Permission denied
    Error: Process completed with exit code 126.

The backend job therefore never ran a single test. Commit messages on main asserting a passing
suite were describing local runs; CI had confirmed none of them, while the other three jobs stayed
green so the red X never obviously meant "the backend suite is not running".

It survived because nothing on a Windows machine can see it. `core.fileMode` is false here, so git
neither records nor reports a permission change, and `mvnw.cmd` is what actually runs locally --
the broken mode is invisible until a Linux runner tries to execute the file.

WHAT IT CHECKS
--------------
Not "every script should be executable" -- that rule would be wrong in this repo, and checking it
would produce noise that gets ignored:

  * .husky/pre-commit and .husky/commit-msg have shebangs and are NOT executable, correctly:
    husky's generated .husky/_/ wrapper runs them via `sh -e "$s"`, which needs no exec bit.
  * scripts/*.sh are invoked as `sh scripts/foo.sh`, likewise.
  * backend/docker-entrypoint.sh is chmod +x'd inside the Dockerfile at build time.

The property that actually matters is narrower: a file invoked as `./name` is executed directly by
the kernel, so it MUST carry the exec bit. So this scans .github/workflows/*.yml and .husky/* for
`./name` invocations and requires mode 100755 on tracked files with that basename.

Basename matching rather than resolving each `working-directory:` and `cd` -- a `./mvnw` under
`working-directory: backend` and one inside `(cd backend && ./mvnw ...)` both mean backend/mvnw,
and reimplementing shell and YAML scoping to prove it is more machinery than the check is worth.
The failure mode of the loose match is demanding an exec bit on a file that did not strictly need
one, which is harmless; the failure mode of the precise version is silently missing an invocation
in a `cd` this parser did not model, which is exactly the bug.
"""

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Where a `./name` invocation can appear. Not scripts/ -- a script running a sibling as ./x would
# be worth catching too, but nothing here does, and scanning every shell file for shapes that do
# not occur invites false positives with no matching bug.
SCAN_GLOBS = [".github/workflows/*.yml", ".github/workflows/*.yaml", ".husky/*"]

# `./name` where name is a filename, not a directory path component. Deliberately does not match
# `./` alone or `./dir/` -- only something being executed.
DIRECT_INVOCATION = re.compile(r"(?<![\w./])\./([A-Za-z0-9._-]+)")

EXEC_MODE = "100755"


def tracked_modes():
    """basename -> {path: mode} for every tracked file, from the index (not the worktree).

    The index is the authority: with core.fileMode=false the worktree bit is meaningless, and the
    index mode is what actually lands in the commit and gets checked out on the runner.
    """
    out = subprocess.run(
        ["git", "ls-files", "-s"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout
    by_name = {}
    for line in out.splitlines():
        if not line.strip():
            continue
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        by_name.setdefault(Path(path).name, {})[path] = mode
    return by_name


def find_invocations():
    """name -> sorted list of files that invoke it as ./name"""
    invocations = {}
    for pattern in SCAN_GLOBS:
        for path in sorted(REPO_ROOT.glob(pattern)):
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for name in DIRECT_INVOCATION.findall(text):
                rel = path.relative_to(REPO_ROOT).as_posix()
                invocations.setdefault(name, set()).add(rel)
    return {k: sorted(v) for k, v in invocations.items()}


def main():
    by_name = tracked_modes()
    problems = []

    for name, callers in sorted(find_invocations().items()):
        for path, mode in sorted(by_name.get(name, {}).items()):
            if mode != EXEC_MODE:
                problems.append((path, mode, callers))

    if not problems:
        print("Clean -- everything invoked as ./name is executable in the index.")
        return 0

    print("BLOCKED: a file run as ./name is not executable in git's index.\n")
    for path, mode, callers in problems:
        print(f"  {path}  (mode {mode}, expected {EXEC_MODE})")
        print(f"      invoked as ./{Path(path).name} by: {', '.join(callers)}")
    print(
        "\nOn a Linux runner this fails with 'Permission denied' and exit code 126 before the\n"
        "command runs at all. Fix it in the index -- chmod alone does nothing here, because\n"
        "core.fileMode is false on Windows checkouts:\n"
    )
    for path, _, _ in problems:
        print(f"    git update-index --chmod=+x {path}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Fails when the three API clients disagree about which endpoints are unauthenticated.

WHY THIS EXISTS, AND WHY IT IS A CHECK RATHER THAN SHARED CODE
--------------------------------------------------------------
`mobile/src/api/client.ts` was ported from `frontend/src/api/client.ts`. The web version was later
fixed so that a 401 from *any* auth endpoint would not trigger a token refresh. The mobile copy
kept excluding only `/auth/refresh`.

The consequence was not cosmetic. For a signed-out user with a stale refresh token still in
SecureStore, one mistyped password sent that token to `/auth/refresh` -- and presenting an
already-rotated refresh token is exactly what `RefreshTokenService.rotate()` treats as theft. It
responds by revoking every active session for that user. A typo on the sign-in screen could sign
you out on every device. Fixed in 5972c97.

The obvious response is to move the rule into a shared module. That was tried and backed out. The
rule is ~20 lines of pure functions; sharing them across three independently built and
independently deployed apps costs a Metro `watchFolders` config (which Expo's own SDK 52+ guidance
says not to hand-write), Vite and Vitest aliases in two apps, TypeScript path mappings in three,
and a build-time dependency from every app onto a directory outside its own root -- including on
Cloudflare Pages, where the build context could not be verified from here. That is a permanent
increase in build-system complexity to deduplicate twenty lines.

What actually went wrong was not that the code was duplicated. It was that the duplication was
unenforced: nothing failed when one copy was fixed and the others were not. So this enforces it,
and the copies stay. If the shared surface ever grows past pure constants -- shared validation,
shared DTOs, real business policy -- revisit; at that point the plumbing may earn its cost.

WHAT IT CHECKS
--------------
1. All three clients list the same unauthenticated endpoints.
2. Each client consults that list in BOTH places it matters: when deciding whether to attach a
   bearer token, and when deciding whether a 401 should trigger a refresh. Checking only the list
   would not have caught the real bug -- mobile's list was correct; it was the second usage that
   was missing.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

CLIENTS = [
    REPO_ROOT / "frontend" / "src" / "api" / "client.ts",
    REPO_ROOT / "admin-portal" / "src" / "api" / "client.ts",
    REPO_ROOT / "mobile" / "src" / "api" / "client.ts",
]

LIST_RE = re.compile(r"const\s+AUTH_ENDPOINTS_NO_TOKEN\s*=\s*\[(.*?)\]", re.S)
ENTRY_RE = re.compile(r"['\"]([^'\"]+)['\"]")

# Both call sites use this shape. Counting them is what detects "the list is right but only one of
# the two decisions consults it", which is precisely how the shipped bug looked.
USAGE_RE = re.compile(r"AUTH_ENDPOINTS_NO_TOKEN\.some\s*\(")
REQUIRED_USAGES = 2


def rel(path: Path) -> str:
    return str(path.relative_to(REPO_ROOT)).replace("\\", "/")


def main() -> int:
    problems = []
    lists = {}

    for path in CLIENTS:
        if not path.exists():
            problems.append(f"{rel(path)}: expected an API client here, but the file is missing.")
            continue

        source = path.read_text(encoding="utf-8")

        match = LIST_RE.search(source)
        if not match:
            problems.append(
                f"{rel(path)}: no AUTH_ENDPOINTS_NO_TOKEN declaration found. Every client must "
                f"declare the unauthenticated endpoints explicitly so this check can compare them."
            )
            continue
        lists[rel(path)] = tuple(sorted(ENTRY_RE.findall(match.group(1))))

        usages = len(USAGE_RE.findall(source))
        if usages < REQUIRED_USAGES:
            problems.append(
                f"{rel(path)}: consults AUTH_ENDPOINTS_NO_TOKEN {usages} time(s), expected at least "
                f"{REQUIRED_USAGES} (once to decide whether to attach a bearer token, once to decide "
                f"whether a 401 should trigger a refresh).\n"
                f"    This exact shape -- correct list, only one of the two decisions using it -- is "
                f"how a mistyped password came to revoke every session on every device."
            )

    distinct = set(lists.values())
    if len(distinct) > 1:
        problems.append("The clients do not agree on which endpoints are unauthenticated:")
        for name, entries in sorted(lists.items()):
            problems.append(f"    {name}\n        {', '.join(entries)}")
        problems.append(
            "    These must match. They mirror the backend's permitAll routes (SecurityConfig), "
            "so a difference means at least one client is wrong about the server."
        )

    if problems:
        print("COMMIT BLOCKED: the API clients' authentication policy has drifted.\n", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print(
            "\n  Fix by making the clients agree. See this script's docstring for the incident "
            "that motivated it.",
            file=sys.stderr,
        )
        return 1

    print(f"Clean -- all {len(CLIENTS)} API clients agree on the unauthenticated endpoints.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

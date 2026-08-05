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
3. Every endpoint a client calls unauthenticated is genuinely permitAll on the BACKEND.

THE THIRD CHECK, AND WHY IT WAS ADDED
-------------------------------------
This script asserted, in its own failure message, that the client lists "mirror the backend's
permitAll routes (SecurityConfig)" -- and never opened that file. It compared the three clients
only to each other, which means a uniform error passes: get the same endpoint wrong in all three
and this reported "Clean". The guard checked a proxy (clients agree) for the property it actually
cared about (clients match the server).

The dangerous direction is a client treating an endpoint as unauthenticated when the server
requires auth: the request goes out with no bearer token, comes back 401, and the client's 401
handler is specifically told not to refresh for these paths -- so the user just sees a failure it
cannot recover from. That direction is a hard failure below.

The reverse direction -- the backend permits something no client lists -- is not dangerous today
but is not nothing either, so it is recorded explicitly rather than ignored. See
BACKEND_ONLY_PERMITALL. This mirrors check-dependency-advisories.py's convention: an entry is
either written down with a reason, or it fails. There is no third state where it is merely
tolerated.
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

SECURITY_CONFIG = (
    REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "finora" / "config" / "SecurityConfig.java"
)

# The API prefix the clients' paths are relative to. Client lists say "/auth/login"; SecurityConfig
# says "/api/v1/auth/**".
API_PREFIX = "/api/v1"

# Backend routes that are permitAll but which no client lists as unauthenticated. Each needs a
# reason that could turn out to be WRONG, and what would change the answer -- the same bar
# check-dependency-advisories.py sets for an accepted advisory.
BACKEND_ONLY_PERMITALL = {
    "/auth/logout": (
        "Clients deliberately send their bearer token with logout. It is permitAll server-side so\n"
        "      that logging out still works with an already-expired access token (the refresh token\n"
        "      in the body is what actually identifies the session to revoke), but sending the token\n"
        "      when there is one is harmless and lets the server attribute the action.\n"
        "      REVISIT IF: logout ever starts REQUIRING authentication. Then a client holding an\n"
        "      expired access token would 401, the 401 handler would refresh with an\n"
        "      already-rotated refresh token, and RefreshTokenService.rotate() treats a replayed\n"
        "      token as theft -- revoking every session on every device, which is the exact\n"
        "      incident this script exists to prevent."
    ),
}

LIST_RE = re.compile(r"const\s+AUTH_ENDPOINTS_NO_TOKEN\s*=\s*\[(.*?)\]", re.S)
ENTRY_RE = re.compile(r"['\"]([^'\"]+)['\"]")

# .requestMatchers("/api/v1/auth/**").permitAll() and the HttpMethod-qualified variant
# .requestMatchers(HttpMethod.GET, "/api/v1/setup/status").permitAll(). Only matchers whose chain
# actually ends in permitAll() count -- authenticated() and hasAuthority() use the same shape.
PERMIT_ALL_RE = re.compile(
    r"\.requestMatchers\s*\(\s*(?:HttpMethod\.\w+\s*,\s*)?(?P<paths>[^)]*?)\)\s*\.permitAll\s*\(\s*\)",
    re.S,
)


def backend_permit_all_patterns() -> tuple[list[str], list[str]]:
    """Path patterns SecurityConfig permits without authentication, and any problems reading it."""
    if not SECURITY_CONFIG.exists():
        return [], [
            f"{rel(SECURITY_CONFIG)}: not found. This check compares the clients against the "
            f"backend's own permitAll routes, so it cannot run without it. If SecurityConfig "
            f"moved, update SECURITY_CONFIG in this script -- do not delete the comparison."
        ]

    source = SECURITY_CONFIG.read_text(encoding="utf-8")
    patterns = []
    for match in PERMIT_ALL_RE.finditer(source):
        patterns.extend(ENTRY_RE.findall(match.group("paths")))

    if not patterns:
        # A regex that matches nothing passes every run and looks identical to a clean tree --
        # the same silently-vacuous failure mode check-imports.py grew a --self-test for.
        return [], [
            f"{rel(SECURITY_CONFIG)}: found no permitAll route matchers at all. Either the file's "
            f"shape changed or PERMIT_ALL_RE no longer matches it. Refusing to report success on "
            f"a comparison that did not actually happen."
        ]
    return patterns, []


def is_permitted_by_backend(client_path: str, patterns: list[str]) -> bool:
    """Whether a client-relative path (e.g. "/auth/login") is covered by a backend permitAll."""
    full = API_PREFIX + client_path
    for pattern in patterns:
        if pattern.endswith("/**"):
            if full.startswith(pattern[:-3] + "/") or full == pattern[:-3]:
                return True
        elif pattern.endswith("/*"):
            prefix = pattern[:-2]
            if full.startswith(prefix + "/") and "/" not in full[len(prefix) + 1:]:
                return True
        elif pattern == full:
            return True
    return False


def backend_auth_routes_not_claimed(patterns: list[str], claimed: set[str]) -> list[str]:
    """permitAll routes under /auth that no client lists -- the informational direction.

    Scoped to /auth because that is the surface these client lists describe. Endpoints like
    /actuator/health and /swagger-ui/** are permitAll but are not called by any client at all, so
    demanding they appear in a client's list would be noise.
    """
    unclaimed = []
    for pattern in patterns:
        if not pattern.startswith(API_PREFIX + "/auth"):
            continue
        if pattern.endswith("/**"):
            # A wildcard cannot be compared entry-by-entry; the concrete routes under it are what
            # matter, and those are enumerated from the controller, not from here.
            continue
        route = pattern[len(API_PREFIX):]
        if route not in claimed and route not in BACKEND_ONLY_PERMITALL:
            unclaimed.append(route)
    return unclaimed

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

    # The comparison this script always claimed to make. Runs even when the clients disagree with
    # each other -- each list is still checked against the server on its own, so a single run
    # reports every problem rather than only the first kind found.
    patterns, config_problems = backend_permit_all_patterns()
    problems.extend(config_problems)

    if patterns:
        for name, entries in sorted(lists.items()):
            for entry in entries:
                if not is_permitted_by_backend(entry, patterns):
                    problems.append(
                        f"{name}: treats '{entry}' as unauthenticated, but SecurityConfig does not "
                        f"permitAll it.\n"
                        f"    The client will send this request with no bearer token, get a 401, and "
                        f"then NOT refresh -- because this list is exactly what tells the 401 handler "
                        f"not to. The user sees a failure they cannot recover from by retrying.\n"
                        f"    Either add the route to SecurityConfig's permitAll matchers, or remove "
                        f"it from every client's list."
                    )

        claimed = {entry for entries in lists.values() for entry in entries}
        unclaimed = backend_auth_routes_not_claimed(patterns, claimed)
        for route in unclaimed:
            problems.append(
                f"SecurityConfig permits '{API_PREFIX}{route}' without authentication, but no client "
                f"lists it.\n"
                f"    That is not automatically wrong -- a client may deliberately send its token to "
                f"an endpoint that does not require one. But it must be a decision, not an "
                f"oversight.\n"
                f"    Add it to every client's AUTH_ENDPOINTS_NO_TOKEN, or record it in this "
                f"script's BACKEND_ONLY_PERMITALL with a reason and what would change the answer."
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

    print(
        f"Clean -- all {len(CLIENTS)} API clients agree on the unauthenticated endpoints, "
        f"and every one of them is permitAll in SecurityConfig "
        f"({len(patterns)} permitAll matchers read)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

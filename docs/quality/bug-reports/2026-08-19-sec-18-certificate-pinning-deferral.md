# SEC-18: Certificate pinning — evaluated, deferred

Part of the Security & Production Hardening roadmap, Sprint 4 (mobile hardening). See
`docs/project-management/plans/project-plan-v1.0.md` §4b for the full backlog this item comes from.

## Current state (verified)

`mobile/src/api/client.ts` talks to the backend over a single `axios` instance whose `baseURL`
comes straight from `EXPO_PUBLIC_API_BASE_URL` (see `client.ts:15-23`). Transport security is
whatever the OS gives a plain HTTPS request by default — the platform trust store validates the
server's certificate chain against public CAs, and nothing in this app pins a specific certificate
or public key. There is no pinning library in `mobile/package.json`, and no Network
Security Config / ATS exception is declared in `app.config.ts`. This matches the finding as
originally raised: the mobile app has no additional defense if a device's OS trust store is itself
compromised (a malicious enterprise/root CA installed on the device, or a state-level MITM CA).

## Why this finding is deferred rather than implemented

Pinning is a fail-hard control: get it right and it closes the trust-store-compromise gap; get it
wrong and every install stops being able to reach the backend at all, with no server-side fix
available — only a client update, which for mobile means an app-store review cycle before it
reaches most users. That failure mode is categorically different from the rest of Sprint 4 (a
missed biometric lock or root-warning banner degrades gracefully; a bad pin is an outage). Shipping
it blind is worse than not shipping it, for reasons specific to this repo's current state rather
than pinning in general:

1. **No production certificate material available to this session.** A correct pin is either the
   leaf certificate's public key hash or an intermediate/CA public key hash, taken from the actual
   certificate the production backend origin currently serves. This session has no access to that
   origin's live TLS handshake or to Railway's dashboard/CLI to retrieve it. Any pin value written
   without that would be a guess, not a security control.
2. **No documented rotation process to pin against.** Pinning safely requires at least two live
   pins (current + backup) so a routine cert renewal doesn't brick the app the moment the old
   cert expires — that requires a runbook for who updates the backup pin before each rotation and
   ships a build with it ahead of time. Railway-managed TLS certs rotate on their own schedule;
   nothing in this repo currently tracks or automates that against a client-side pin set.
3. **No device to verify a real pin failure/success path on.** This environment cannot run the
   compiled native app against the production origin (see the Sprint 4 PR description for the
   general native-verification limitation this session operates under). A pinning implementation
   that has never been exercised against a real handshake — success, expected rotation, and a
   deliberately-wrong pin to confirm it actually rejects — is exactly the kind of "looks right,
   never verified" change the standing engineering rules for this roadmap call out.

Implementing a plausible-looking pin against a guessed or stale certificate would satisfy the
backlog item on paper while creating a real risk this codebase doesn't have today: a client that
silently stops trusting its own backend after the next routine cert renewal.

## What unblocks this

Before implementing:
- The current production backend's certificate (or at minimum its SPKI public key hash) and at
  least one backup hash for the next planned rotation, obtained from whoever manages the Railway
  TLS config.
- A short rotation runbook: who updates the backup pin, and when, relative to each cert renewal.
- A real device or simulator build able to reach the production (or a TLS-equivalent staging)
  origin, to verify both the happy path and a deliberately-broken pin actually blocks the request
  instead of silently falling back to unpinned trust.

## Status

Deferred, not implemented. Tracked as the one open item under Sprint 4 / SEC-18. No code changes
in this document.

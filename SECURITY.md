# Security Policy

Finora is a personal financial operating system that processes bank statements and other
sensitive financial data. We take security seriously and appreciate responsible disclosure of
any vulnerabilities.

## Supported Versions

Finora is deployed continuously from the `main` branch — there is no versioned release line.
Only `main` is supported; a fix lands as a new commit rather than a backport.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately using GitHub's [Private Vulnerability Reporting](https://github.com/siddharth705/finora/security/advisories/new)
feature (Security tab → "Report a vulnerability"). This opens a private advisory visible only to
the maintainer until a fix is ready.

Please include:
- A description of the vulnerability and its potential impact
- Steps to reproduce (a minimal example if possible)
- Any relevant logs, requests, or screenshots

## What to Expect

- Acknowledgement within 5 business days
- An assessment of severity and affected scope
- A fix timeline appropriate to severity — critical issues (auth bypass, data exposure across
  users, RCE) are prioritized immediately
- Credit in the fix's commit/PR description, if desired

## Scope

In scope: the backend API, web frontend, admin portal, and mobile app in this repository.

Out of scope: third-party services Finora integrates with (Railway, Cloudflare, Firebase,
Google/Apple OAuth) — report those directly to the respective provider.

## Automated Security Tooling

This repository already runs:
- **Dependabot** — scheduled dependency updates ([`.github/dependabot.yml`](.github/dependabot.yml))
  plus vulnerability alerts and automated security-fix PRs for known CVEs across Maven, npm, and
  Docker dependencies
- **Secret scanning with push protection** — blocks commits containing recognizable credential
  patterns before they reach the repository
- **[`scripts/check-dependency-advisories.py`](scripts/check-dependency-advisories.py)** — gates
  CI on npm advisories in shipped frontend/admin-portal/mobile code, with a maintained allowlist

See [`docs/quality/tooling/ENGINEERING_TOOLING_ROADMAP.md`](docs/quality/tooling/ENGINEERING_TOOLING_ROADMAP.md)
for planned additions (Semgrep, Trivy image scanning, OWASP ZAP).

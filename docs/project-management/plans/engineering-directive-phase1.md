# Finora Engineering Roadmap & Development Standards

## Phase 1 — Architectural Foundation & Engineering Directive

> This document is the architecture vision and Phase 1 directive — it does not track current
> execution status. For where the project actually stands (completion, risks, dependencies,
> release gates), see the living plan:
> [`project-plan-v1.0.md`](project-plan-v1.0.md).

### Executive Summary

As Finora evolves, we must align on a critical strategic objective before implementing additional business features.

Over the past few weeks, the team has made incredible progress building core capabilities, including statement imports, automatic account creation, and user dashboard features. However, to scale Finora reliably, we must now invest in strengthening the application's core architectural foundation.

**The Vision:** Finora is not being built as a simple expense tracker. The goal is to build a world-class financial platform capable of evolving into a complete Financial Operating System for individuals and businesses.

The difference between a prototype and an enterprise-grade platform is not feature count — it is foundational quality. For our upcoming sprint, we are pausing new feature development to focus 100% on core engineering standards and platform architecture.

### Our Engineering Philosophy

Every technical decision made from this point forward must answer one fundamental question:

> "Will this still be the right architectural decision when Finora handles hundreds of thousands of users, complex financial datasets, and a distributed engineering team?"

If the answer is no, we reconsider the approach.

### Core Architectural Principles

- **Scalability:** Code and database schemas must scale horizontally without requiring rewrites.
- **Security:** Financial data demands zero-trust architecture, strict resource-level authorization, and encrypted storage.
- **Maintainability & Clean Architecture:** Strict separation of concerns (Controllers, Services, Repositories, DTOs).
- **Developer Experience (DX):** Automated toolchains, standardized commits, and seamless local dev environments.
- **Reliability & Extensibility:** Predictable failure modes, centralized exception handling, and modular service boundaries.

---

## Phase 1 Objectives & Deliverables

The goal of Phase 1 is not to ship user-facing features. The goal is to establish the technical standards, security models, and version control workflows that every future module will follow.

### Priority 1 — Source Control & Version Control Strategy (Highest Priority)

Developing without a structured Git workflow is our highest technical risk. Every engineering process — CI/CD, code reviews, automated testing, and release management — depends on a disciplined version control pipeline.

**1. Repository Setup & Safety**

- Initialize Git and push the complete codebase to our centralized private repository.
- Configure a strict `.gitignore` to prevent environment leaks, build artifacts, local databases, or credentials from entering Git history (`node_modules`, `target/`, `build/`, `.env.local`, logs, IDE configs).
- Install Pre-Commit Hooks (Husky / Commitlint) to automatically enforce linting and commit message format before code is committed.

**2. Branching Strategy**

> **Reality check (updated):** The GitFlow model below reflects the original Phase 1
> engineering directive. The repository has since adopted a trunk-based workflow instead —
> `main` as the trunk, short-lived `feature/*` / `fix/*` / `chore/*` / `ci/*` / `docs/*`
> branches, and releases as tags on `main` rather than a `release/*` branch. The current,
> authoritative branching rules are maintained in
> [`CONTRIBUTING.md`](../../../CONTRIBUTING.md#branching-strategy). The diagram below is
> preserved as historical context for the original design decision, not as current practice.

Direct commits to `main` or `develop` are strictly prohibited. We will adopt the following workflow:

```
main (Production Releases Only)
  ▲
  │ (Tagged Releases)
release/*
  ▲
develop (Integration Branch)
  ▲
  ├── feature/*   (Isolated Feature Branches)
  ├── bugfix/*    (Defect Patching)
  └── hotfix/*    (Emergency Production Patches)
```

**3. Conventional Commit Standards**

Commits must explain *why* a change was made and adhere to Conventional Commits:

- ❌ bad: `"fixed stuff"`, `"updated parser"`, `"changes"`
- ✅ good: `feat(auth): implement JWT refresh token rotation`
- ✅ good: `fix(import): resolve balance rounding error in CSV parser`
- ✅ good: `refactor(accounts): isolate bank registry into core module`

**4. Semantic Versioning & Tagging**

Follow Semantic Versioning (`MAJOR.MINOR.PATCH`):

- `0.1.0` → `0.2.0` (Development Milestones) → `1.0.0` (Production Baseline)
- Every milestone release must include a tagged Git release and a structured `CHANGELOG.md`.

### Priority 2 — Enterprise Identity & Access Management (IAM)

We will not implement a simple binary "Admin/User" flag. That approach fails as systems scale. Instead, we are building an independent Identity & Access Management (IAM) module based on Role-Based Access Control (RBAC) and Attribute-Based Resource Ownership.

**1. Authentication Architecture**

- **Token Strategy:** Dual-token strategy with short-lived JWT Access Tokens and long-lived Refresh Tokens stored in secure, `HttpOnly`, `SameSite` cookies.
- **Token Rotation:** Implement Refresh Token Rotation to detect token theft automatically.
- **Account Security:** BCrypt/Argon2 password hashing, rate-limited login endpoints, failed login lockouts, and session tracking.
- **Future-Proofing:** Architect the auth service layer to support MFA (TOTP), OAuth2 (Google/Microsoft/GitHub SSO), and Passkeys without breaking current endpoints.

**2. Granular Permission Engine**

Permissions answer: "Is this user allowed to perform this specific action?"

```
User  ➔  Role (Group of Permissions)  ➔  Permission  ➔  Resource  ➔  Action
```

- Roles are simply database-backed groupings of permissions (e.g., `ROLE_USER`, `ROLE_ADMIN`, `ROLE_AUDITOR`).
- Controllers and services check granular permissions (e.g., `@PreAuthorize("hasAuthority('TRANSACTION_EXPORT')")`) rather than hardcoded roles.

**3. Strict Resource Ownership Verification**

Permission to edit transactions does not grant permission to edit *all* transactions. Every service layer request must evaluate Resource Ownership:

```
CanAccessResource = HasPermission(Action) AND (IsResourceOwner(User, Resource) OR HasElevatedOverride(User))
```

### Priority 3 — Infrastructure, Environment & Development Strategy

**1. Single Environment Configuration Architecture**

To keep velocity high, we will maintain a single local development runtime today while structuring configuration files for multi-environment deployments tomorrow:

- **Backend (Spring Boot):** Maintain `application.yml` for base configs and `application-local.yml` for local overrides. (Prepared for `application-dev.yml`, `application-prod.yml`).
- **Frontend (React):** Utilize `.env.local` for local secrets and API URLs.

**2. Docker Infrastructure Strategy**

To ensure instant setup and zero local dependency clutter, Docker will manage local infrastructure services, while application code runs locally with hot-reloading:

- Containerized via Docker Compose: PostgreSQL, Redis (caching), MinIO (object storage).
- Local Run: Spring Boot backend and React frontend run natively on developer host machines for sub-second hot-reloads and debugger attachment.

### Priority 4 — Engineering & Quality Standards

Every feature developed after Phase 1 must strictly follow these engineering guidelines:

1. **Clean Architecture & SOLID:** Controllers must remain thin. Business logic belongs exclusively in the Service layer. Data Access belongs in Repositories.
2. **DTO & Schema Separation:** Database entities must never be returned directly through API endpoints. All API boundaries must enforce strict Data Transfer Objects (DTOs) with request validation (`@Valid` / `Zod`).
3. **Database Migration Control:** Direct manual edits to local or shared database schemas are prohibited. All schema modifications must be committed as versioned migration scripts via Flyway / Liquibase.
4. **API Type-Safety Contract:** All backend REST endpoints must expose OpenAPI/Swagger specs (`/swagger-ui.html`). Frontend API interfaces must be generated or strongly typed directly against backend DTO models.
5. **Centralized Exception Handling:** Global exception handlers (`@ControllerAdvice` / custom error boundaries) must catch errors and return uniform JSON error responses across the application.

---

## Existing Engineering Capabilities

The following platform capabilities have since been built and documented elsewhere. Future
phases should extend these rather than re-proposing parallel implementations from scratch.

**Financial data integrity** — audit logging (`AuditLog` entity/repository) and document
extraction fidelity are covered by
[`financial-document-intelligence-principles.md`](../../architecture/system-design/financial-document-intelligence-principles.md),
notably its "Never lose information" principle.

**Observability** — Spring Boot Actuator health endpoints, Prometheus metrics via Micrometer,
a `DatabaseHealthProvider`, and a `CorrelationIdFilter` for request tracing are already in the
backend.

**Deployment safety** — environment configuration, Docker Compose, and Railway deployment
procedures are maintained in
[`deployment-guide.md`](../../operations/deployment/deployment-guide.md).

---

## Long-Term Platform Roadmap

Once this foundational architecture is established, our subsequent development phases will proceed on top of a rock-solid platform:

```
Phase 1: Architectural Foundation (Git Workflow, IAM, DTOs, Docker Setup)
   │
   ├── Phase 2: Enhanced Statement Processing Engine (CSV & LLM PDF Extraction)
   │
   ├── Phase 3: Bank Registry, UI Presentation Layer & Rich Analytics
   │
   ├── Phase 4: Budgets, Financial Goals & Rules Engine
   │
   └── Phase 5: AI Insights, Multi-Tenant Workspaces & Production Deployment
```

---

## Final Note

Finora will not become a project where we patch technical debt every few months because we moved too fast in the beginning.

By investing the required effort now, every feature we build tomorrow will be easier to develop, faster to test, safer to deploy, and effortless to scale.

Let's treat this milestone as the exact point where Finora transitions from a prototype into a professionally engineered, enterprise-grade software platform.

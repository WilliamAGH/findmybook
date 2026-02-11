# Agent Operations Manual

## Document Organization [ORG]
- [ORG1] Keep enforcement-critical rules within the first 200 lines.
- [ORG2] Start every enforceable rule with a hash prefix (`AAA1` or `AAA1a`).
- [ORG3] Keep section order fixed: Rule Summary -> Foundational -> Blocking -> Architecture -> Code Quality -> Data/API -> Frontend -> Process -> Meta.
- [ORG4] Add hashes without renumbering or reusing retired hashes.
- [ORG5] One hash maps to one rule statement only.
- [ORG6] Use directive language (`MUST`, `MUST NOT`, `NEVER`); avoid discretionary wording.

## Rule Summary [SUM]
- [SRC1] MUST verify behavior with repository code and documentation before acting.
- [DOC0] MUST read prerequisite docs by path before editing matching source paths.
- [SCO1] MUST confirm objective and scope before edits and preserve request traceability.
- [FIL1] MUST NOT create files without explicit user approval after exhaustive reuse search.
- [BLK1] MUST fix root causes; no hidden degradation, no silent fallbacks, no fake data.
- [TYP0] Type safety is blocking: no raw types, unchecked casts, `Object`, or `Map<String,Object>`.
- [ERR0] Exception handling is blocking: no swallowed errors, no broad catch-all handling, no empty catches.
- [ARC1] MUST follow clean architecture boundaries and move net-new backend work to canonical package roots.
- [BE1] Backend flow MUST remain Controller -> Service/Use Case -> Repository/Adapter.
- [FE1] Net-new frontend features MUST target Svelte 5 + Vite; Thymeleaf is maintenance-only.
- [FE2] Frontend data access MUST be API-only with typed validation at the boundary.
- [FE3] Frontend state/testing/styling MUST use bounded state, Vitest+Testing Library, and Tailwind-first rules.
- [CLN1] Keep code small and single-purpose; remove dead code and magic literals.
- [NAM1] Use intent-revealing American English names.
- [DOC1] Public types/methods require Javadocs that explain why + what.
- [DEP0] Deprecated API usage is blocking: no new deprecated APIs and no deprecated call sites in touched code.
- [DB1] Manual SQL only; no Flyway/Liquibase and no auto-migrations.
- [API1] API contracts MUST remain explicit, strongly typed, and documented when changed.
- [UPD1] Update all impacted usages across Java, templates, JS/TS, SQL, configs, and tests.
- [GIT1] No destructive git operations, branch changes, commits, or pushes without explicit user approval.
- [LOC1] File size ceilings are mandatory; large touched legacy files require split plans.
- [TST1] Behavior changes require tests (unit first, integration as needed).
- [VER1] Validate changes with repository-standard build/test/runtime checks.
- [ENV1] Use Spring Boot 4.0.x, Java 25, port 8095 defaults, and documented run commands.

## Foundational
### [SRC1] Source Verification
- [SRC1a] Read impacted code paths before proposing or implementing changes.
- [SRC1b] Verify dependency behavior from `build.gradle`, official docs, or dependency source before usage changes.
- [SRC1c] Stop and ask the user when evidence is incomplete or conflicting.
- [SRC1d] Mandatory investigation sequence: read code -> read docs -> verify dependencies -> form/test hypothesis -> implement minimal fix -> run verification.

### [DOC0] Prerequisite Reading by Path
- [DOC0a] Before editing `src/main/java/**` or `src/test/java/**`, read `docs/development.md`.
- [DOC0b] Before editing `src/main/resources/templates/**` or `src/main/resources/static/**`, read `docs/features.md` and `docs/api.md`.
- [DOC0c] Before editing SQL/schema files, read `docs/database_id_strategy.md` and `docs/configuration.md`.
- [DOC0d] Before changing runtime flags or startup behavior, read `docs/troubleshooting.md` and `docs/configuration.md`.

### [SCO1] Scope & Traceability
- [SCO1a] Confirm the objective (bug, feature, constraint) before editing code.
- [SCO1b] Keep work inside agreed scope and call out scope creep immediately.
- [SCO1c] Tie implementation notes and summaries directly to the user request.

### [FIL1] Controlled File Creation
- [FIL1a] Search existing implementations before adding files (`rg --files`, `rg`).
- [FIL1b] Request explicit user approval before creating any new file.
- [FIL1c] Create markdown scratch files only under `tmp/` unless user-directed otherwise.
- [FIL1d] Delete temporary markdown artifacts when the task is complete.
- [FIL1e] MUST edit existing files before introducing parallel structures.

## Blocking
### [BLK1] Root Cause Resolution (Blocking)
- [BLK1a] Fix root causes instead of workaround layers, compatibility shims, or parallel implementations.
- [BLK1b] Do not introduce silent fallback behavior that hides failures.
- [BLK1c] Fallback helpers may only fail explicitly (for example service unavailable); they MUST NOT return fake or placeholder data.
- [BLK1d] Do not add feature-flagged shadow implementations to hedge uncertainty.

### [TYP0] Type Safety & Nullness (Blocking)
- [TYP0a] Ban `Object`, raw generics, unchecked casts, and `Map<String,Object>` in business code.
- [TYP0b] Ban `@SuppressWarnings` in production code; test-only exceptions require explicit user approval.
- [TYP0c] Use records/value objects for typed boundaries and explicit conversions.
- [TYP0d] Use `Optional<T>` for nullable returns only; never use `Optional` parameters.
- [TYP0e] Do not use null as control flow; model absence explicitly through contracts.

### [ERR0] Exceptions, Logging, and Fallbacks (Blocking)
- [ERR0a] Never swallow exceptions, never use empty catch blocks, and never continue silently after failure.
- [ERR0b] Never catch broad `Exception`/`Throwable` in business logic unless rethrowing a typed domain exception immediately.
- [ERR0c] Log failures with context (entity IDs, key inputs, operation) before rethrowing or wrapping.
- [ERR0d] Never replace failed operations with null/empty/default values unless that absence is an explicit contract.

### [DEP0] Deprecated API Usage (Blocking)
- [DEP0a] MUST NOT introduce new usages of APIs annotated `@Deprecated` or marked for removal.
- [DEP0b] MUST migrate all touched deprecated call sites to supported replacements; if no replacement exists, stop and ask the user.
- [DEP0c] MUST NOT annotate net-new production classes or methods with `@Deprecated` without explicit user approval and a documented replacement path.

### [GIT1] Git Safety (Blocking)
- [GIT1a] Treat all uncommitted changes as intentional user work and never revert/discard them.
- [GIT1b] Never run destructive git commands (`reset`, `checkout`, `restore`, `clean`, `stash`, `revert`).
- [GIT1c] Never change branches without explicit user permission.
- [GIT1d] Never commit or push unless the user explicitly asks.
- [GIT1e] Never skip hooks (`--no-verify`, `-n`, `--no-gpg-sign`).

### [LOC1] File Size Ceiling (Blocking)
- [LOC1a] Keep new source files under 350 lines.
- [LOC1b] Start splitting files when they approach 350 lines.
- [LOC1c] For touched legacy files over 350 lines, include a concrete split plan in the summary.

## Architecture
### [ARC1] Canonical Architecture Direction
- [ARC1a] Dependencies point inward; domain logic remains framework-agnostic.
- [ARC1b] Use canonical package roots for net-new backend work: `net.findmybook.boot`, `net.findmybook.application`, `net.findmybook.domain`, `net.findmybook.adapters`, `net.findmybook.support`.
- [ARC1c] Keep cross-layer contracts typed and explicit; no hidden coupling through generic helpers.
- [ARC1d] Migrate incrementally: legacy packages remain operable, but new seams move toward canonical roots.

### [BE1] Backend Layer Boundaries
- [BE1a] Controllers translate HTTP <-> application contracts and delegate to one service/use case.
- [BE1b] Services/use cases own business logic, orchestration, and transaction boundaries.
- [BE1c] Repositories/adapters perform data access only; they do not contain business policy.
- [BE1d] Never import controllers into services or services into repositories.
- [BE1e] Use constructor injection only; field injection is prohibited.
- [BE1f] Read-only service operations MUST use `@Transactional(readOnly = true)` when transaction scopes apply.

### [MOD1] Incremental Modernization
- [MOD1a] Keep existing Thymeleaf routes stable while introducing new UI capability in Svelte/Vite.
- [MOD1b] Refactor by seams: extract cohesive domain/application classes from monolith services as part of touched work.
- [MOD1c] Do not perform broad rewrites without explicit user approval.

## Code Quality
### [CLN1] Clean Code
- [CLN1a] Keep methods small, single-purpose, and readable with guard clauses.
- [CLN1b] Remove dead code and unreachable paths during touched edits.
- [CLN1c] Replace magic literals with named constants.
- [CLN1d] Avoid catch-all utility buckets (`*Utils`, `*Helper`, `*Common`) for net-new code.
- [CLN1e] Use platform defaults over custom patterns unless a proven requirement exists.

### [NAM1] Naming & Language
- [NAM1a] Use intent-revealing names based on domain language.
- [NAM1b] Ban generic names such as `data`, `info`, `value`, `item`, `tmp`, and `obj`.
- [NAM1c] Use American English in code, comments, and docs.

### [DOC1] Documentation & Comments
- [DOC1a] Add Javadocs for public classes, interfaces, enums, and public methods.
- [DOC1b] Write Javadocs that explain why the code exists and what contract it fulfills.
- [DOC1c] Keep inline comments for why-oriented context, not line-by-line narration.

## Data & API
### [DB1] Database & SQL
- [DB1a] Use manual SQL migrations and SQL scripts only.
- [DB1b] Do not introduce Flyway/Liquibase or automatic schema migration on boot.
- [DB1c] Keep SQL typed and parameterized; never format user input into SQL strings.
- [DB1d] Preserve compatibility with the canonical schema in `src/main/resources/schema.sql`.
- [DB1e] Temporary `.sql` planning files may exist, but agents MUST NOT execute ad-hoc schema changes directly.

### [API1] API Contracts
- [API1a] Preserve stable endpoint behavior unless the user requests a breaking change.
- [API1b] Keep request/response contracts explicit and strongly typed.
- [API1c] Update docs in `docs/api.md` when endpoint behavior, parameters, or response shape changes.

## Frontend
### [FE1] Frontend Platform Direction
- [FE1a] Treat `src/main/resources/templates/` Thymeleaf views as maintenance-only for bug fixes and parity work.
- [FE1b] Build net-new frontend features in a Svelte 5 + Vite app structure under `frontend/` after explicit user approval.
- [FE1c] Keep backend-rendered pages functional while migrating feature-by-feature.
- [FE1d] Keep frontend build assets deterministic and checked in only when repository policy requires it.
- [FE1e] Do not introduce SvelteKit SSR into this repository unless the user explicitly requests it.

### [FE2] Frontend Boundaries
- [FE2a] Frontend reads/writes data through backend HTTP endpoints only; never direct DB access.
- [FE2b] Validate boundary payloads with typed schemas in frontend code.
- [FE2c] Keep auth and security enforcement on the backend; frontend does not duplicate trust logic.

### [FE3] Frontend State, Styling, and Testing
- [FE3a] Use local component state or focused module state; avoid unbounded global state.
- [FE3b] Use Vitest + Testing Library for Svelte unit/component tests when Svelte app exists.
- [FE3c] Use Tailwind utility classes as default styling; no inline styles and no `!important`.
- [FE3d] Keep frontend boundaries explicit: API clients, state modules, and components MUST remain separated by responsibility.

## Process
### [UPD1] Comprehensive Updates
- [UPD1a] Search and update all impacted usages across Java, templates, JS/TS, SQL, configs, and tests.
- [UPD1b] Verify no stale references remain after renames or contract changes.
- [UPD1c] Keep summaries explicit about what changed and what was intentionally left unchanged.

### [TST1] Testing Strategy
- [TST1a] Add or update tests for every behavior change.
- [TST1b] Use Arrange-Act-Assert and `should_ExpectedBehavior_When_StateUnderTest` naming for unit tests.
- [TST1c] Mirror source structure under `src/test/java/net/findmybook/...`.
- [TST1d] Keep fixtures under `src/test/resources/fixtures/` and mock responses under `src/test/resources/mock-responses/`.

### [VER1] Verification Commands
- [VER1a] Backend compile/test with `./gradlew clean test` (or targeted tasks for scoped changes).
- [VER1b] Runtime verification command is `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun`.
- [VER1c] Frontend CSS pipeline check is `npm --prefix frontend run build:css`.
- [VER1d] If a Svelte/Vite app is present, verify with its local `dev`, `test`, `check`, and `build` scripts.
- [VER1e] For UI changes, verify both desktop and mobile rendering paths for affected pages or routes.

### [ENV1] Technology & Runtime Defaults
- [ENV1a] Backend baseline is Spring Boot 4.0.x with Java 25 idioms.
- [ENV1b] Default local port is `8095` unless explicitly overridden.
- [ENV1c] Production URL is `https://findmybook.net`.
- [ENV1d] Primary data store is PostgreSQL; schema reference lives in `src/main/resources/schema.sql`.

## Meta
### [ORG9] Authority & Evolution
- [ORG9a] `AGENTS.md` is the authoritative enforcement surface for agent behavior in this repo.
- [ORG9b] Keep rules concise and imperative; avoid ambiguous language.
- [ORG9c] When rules evolve, preserve compatibility with existing project constraints and document the new direction clearly.

### [DOC2] Documentation Discipline
- [DOC2a] Do not create documentation index/barrel files whose primary purpose is linking other docs.
- [DOC2b] Keep docs evergreen; avoid task logs, dated notes, and PR-specific narratives in canonical docs.
- [DOC2c] When behavior changes, update the exact source-of-truth doc in `docs/` within the same change.

---
This manual replaces prior local agent rule files; treat this file as the single source of truth.

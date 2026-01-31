# Agent Operations Manual

## Document Organization [ORG]
- Purpose: keep critical rules in the first 200 lines and cite them by hash prefix.
- Hash prefix format: `AAA1` (3–4 uppercase letters + number). Every rule statement starts with its hash and is 100% enforced.
- Structure: Rule Summary first, then Details by hash. Add new hashes without renumbering.

## Rule Summary [SUM]
- SRC1 Never make assumptions; verify with code/docs and ask when unsure.
- SCO1 Confirm the why and scope before changes; keep traceability to the user request.
- FIL1 New files require exhaustive search, confirmation no existing solution, and explicit permission; markdown files only in `tmp/` unless requested and must be deleted when done.
- TYP1 Type safety only: no `Object`, `Map<String,Object>`, raw types, unchecked casts, or `@SuppressWarnings`.
- ARC1 Clean architecture: dependencies point inward; domain has zero framework imports; Controller → Service → Repository.
- CLN1 Clean code: small single-purpose methods, no dead code, no empty try/catch, use named constants, avoid generic utilities.
- NAM1 Intent-revealing names; American English only.
- UPD1 When changing code, update all usages across the repo.
- JVA1 Java 25+/Spring Boot 3.4 idioms: constructor injection, immutability, records, Optional returns only.
- ERR1 Specific exceptions only; never swallow errors; log with context and rethrow or wrap.
- DOC1 Javadocs required for public types/methods; explain why + what; comments explain why only.
- TST1 Tests required for behavior changes; follow pyramid and naming; use Mockito for unit tests.
- CSS1 Tailwind utility-first; no inline styles or `!important`; avoid custom CSS unless necessary.
- FIL2 500-line max per file (plan split at ~400); avoid monoliths.
- DB1 Manual SQL only; no Flyway/Liquibase; no auto migrations; respect schema definitions.
- GIT1 Git safety: no branch changes, commits, config edits, destructive commands, or hook skipping without explicit approval.
- ENV1 Defaults: port 8095, `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun`, production https://findmybook.net.

## Details

### [SRC1] Source Verification
- Review the current codebase and documentation before answering or changing behavior.
- If unsure, stop and ask rather than guessing.
- Never rely on memory for APIs, framework patterns, or dependency versions.

### [SCO1] Scope & Traceability
- Confirm the underlying goal (bug, feature, constraint) before making changes.
- Keep work within the agreed scope; call out scope creep early.
- Reference the current request in code comments and summaries when relevant.

### [FIL1] Controlled File Creation
- Before any new file: search exhaustively, analyze reuse, confirm no existing solution, request explicit permission.
- Do not create any new file without user approval.
- Markdown files must be created in `tmp/` unless explicitly requested elsewhere, and must be deleted when no longer needed.

### [TYP1] Type Safety
- Use typed DTOs/records and value objects; never use raw types or `Map<String,Object>`.
- No `@SuppressWarnings`; fix the root cause.
- Prefer explicit, safe conversions (for example, `Number::intValue`) when needed.

### [ARC1] Clean Architecture
- Dependencies point inward; domain has zero framework imports.
- Controllers translate HTTP ⇄ domain and call services; services own business logic; repositories access data.
- Never have services import controllers or repositories import services.

### [CLN1] Clean Code
- Keep methods small, focused, and single-responsibility.
- Remove dead code and unused paths.
- Never swallow exceptions or add empty catch blocks.
- Replace repeated literals with named constants.
- Avoid generic catch-all helpers (`*Utils`, `*Helper`, `*Common`).

### [NAM1] Naming & Language
- Use intent-revealing names; avoid generic names like `data`, `info`, `value`, `item`, `tmp`.
- Prefer domain terms; abbreviations only when standard.
- Use American English only in code and comments.

### [UPD1] Comprehensive Updates
- When changing code, find and update all usages across Java, tests, templates, configs, SQL, and JS.
- Use repo-wide searches before and after changes to verify completion.

### [JVA1] Java & Spring Boot Idioms
- Use Java 25+ features (records, pattern matching, sealed types) where appropriate.
- Prefer immutable objects and `final` fields.
- Constructor injection only; never field injection.
- Use `Optional<T>` for nullable return values; do not use Optional parameters.
- Use `@Transactional(readOnly = true)` for read-only service methods.

### [ERR1] Error Handling & Logging
- Catch specific exceptions only; avoid broad `Exception` catches.
- Log with context (identifiers, inputs) and rethrow or wrap with a domain exception.
- Never hide errors with silent fallbacks.

### [DOC1] Documentation
- Javadocs are required for public classes, interfaces, enums, and public methods.
- Javadocs must explain why the code exists and what it does.
- Comments should explain why, not what.

### [TST1] Testing
- Add or update tests whenever behavior changes.
- Follow the test pyramid: unit → integration → minimal E2E.
- Unit tests: Mockito, Arrange‑Act‑Assert, `should_ExpectedBehavior_When_StateUnderTest` naming.
- Tests mirror source structure under `src/test/java/com/williamcallahan/...`.
- Use fixtures in `src/test/resources/fixtures/` and mock responses in `src/test/resources/mock-responses/`.

### [CSS1] Styling (Tailwind)
- Tailwind utility classes are the default; avoid custom CSS unless required.
- No inline styles and no `!important`, ever.
- Avoid arbitrary values unless justified; prefer theme variables (`variables.css`).
- If custom CSS is necessary, use `src/main/resources/static/css/main.css` with BEM naming.

### [FIL2] File Size & Structure
- Maximum 500 lines per file; start splitting around 400.
- Extract focused classes/services/fragments with user approval per [FIL1].

### [DB1] Database & Migrations
- Manual SQL only; never use Flyway or Liquibase.
- Do not enable automatic migrations on boot.
- Temporary `.sql` files are allowed for planning but must not be executed by LLMs.
- All schema changes must reference immutable MySQL and PostgreSQL schema definitions.

### [GIT1] Git Operations & Safety
- Never change branches without explicit permission.
- Never commit unless the user explicitly asks.
- Never edit git config (user.name/user.email).
- Never run destructive git commands or force pushes without explicit approval.
- Never skip hooks (`--no-verify`, `--no-gpg-sign`, `-n`).

### [ENV1] Technology & Environment Defaults
- Framework: Spring Boot 3.4.x with Java 25+ idioms.
- Styling: Tailwind CSS utility-first (no custom CSS unless necessary).
- Databases: MySQL (primary) and PostgreSQL (reference schema).
- Default port: 8095 (override with `SERVER_PORT`).
- Local start: `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun`.
- Production site: https://findmybook.net.

---
This manual replaces prior `.cursorrules` and `claude.md` content; refer to `AGENTS.md` as the authoritative guide going forward.

# Development Guide

## Prerequisites
- **Java 25**
- **Gradle** (via `./gradlew`)
- **Node 22.17.0** (for `frontend/` Svelte 5 + Vite 7)

## Shortcuts

| Command | Description |
|---------|-------------|
| `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun` | Run in dev mode |
| `npm --prefix frontend run dev` | Run Svelte SPA with Vite dev server |
| `npm --prefix frontend run check` | Type-check Svelte/TS frontend |
| `npm --prefix frontend run test` | Run frontend Vitest suite |
| `npm --prefix frontend run build` | Build frontend assets into Spring static resources |
| `./gradlew clean classes -x test` | Quick clean + compile without tests |
| `./gradlew test` | Run tests only |
| `./gradlew clean test` | Full backend + frontend verification |
| `SPRING_PROFILES_ACTIVE=nodb ./gradlew bootRun` | Run without database |
| `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun` | Run in production mode |
| `./gradlew dependencies` | Display dependencies |
| `./gradlew bootJar` | Build JAR |

## JVM Configuration
If you encounter warnings, export the following:
```bash
export GRADLE_OPTS="-XX:+EnableDynamicAgentLoading -Xshare:off"
```

## Code Analysis
- **Dependency Analysis:** `./gradlew dependencies`

## Frontend Build Integration
- `processResources` depends on frontend `npm run build`.
- `check` depends on frontend `npm run check` and `npm run test`.
- Use `-PskipFrontend` for backend-only loops when needed:
  - Example: `./gradlew test -PskipFrontend`

# Development Guide

## Prerequisites
- **Java 25**
- **Gradle** (via `./gradlew`)

## Shortcuts

| Command | Description |
|---------|-------------|
| `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8095 ./gradlew bootRun` | Run in dev mode |
| `./gradlew clean classes -x test` | Quick clean + compile without tests |
| `./gradlew test` | Run tests only |
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

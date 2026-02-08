##
## Multi-stage build for Java Spring Boot application (Gradle + Java 25)
## Base registry defaults to AWS ECR Public mirror but can be overridden
ARG BASE_REGISTRY=public.ecr.aws/docker/library

# ---------- Build stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk AS build
WORKDIR /app

# 0. Install Node.js for frontend build
# Using NodeSource to get Node.js 22.x (required by Vite/Svelte)
RUN set -eux; \
    apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates gnupg; \
    curl -fsSL https://deb.nodesource.com/setup_22.x -o /tmp/nodesource_setup.sh; \
    bash /tmp/nodesource_setup.sh; \
    apt-get install -y --no-install-recommends nodejs; \
    rm -rf /var/lib/apt/lists/* /tmp/nodesource_setup.sh

# 1. Gradle wrapper & configuration (rarely changes)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x ./gradlew

# 2. Pre-fetch Gradle dependencies (layer cached until build scripts change)
RUN ./gradlew dependencies --no-daemon -q

# 3. Frontend dependency install (cached until package.json/lock changes)
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci

# 4. Frontend config and source files (surgical copies avoid node_modules)
COPY frontend/index.html ./frontend/
COPY frontend/vite.config.ts frontend/tsconfig.json frontend/svelte.config.js frontend/tailwind.config.js ./frontend/
COPY frontend/public ./frontend/public
COPY frontend/src ./frontend/src

# 5. Java sources
COPY src ./src

# 6. Build JAR (Frontend build is triggered via Gradle processResources task)
# -q reduces noise, -x test skips tests for faster build
RUN ./gradlew bootJar --no-daemon -q -x test

# ---------- Extractor stage for layered JAR ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre AS extractor
WORKDIR /app
COPY --from=build /app/build/libs/findmybook-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --application-filename application.jar --destination extracted

# ---------- Runtime stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre AS runtime
WORKDIR /app
ENV SERVER_PORT=8095
ENV JAVA_TOOL_OPTIONS="--enable-preview -XX:MaxRAMPercentage=75.0 -Dio.netty.noUnsafe=true"
EXPOSE 8095

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# Copy the extracted layers individually for optimal Docker caching
COPY --from=extractor --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser

# Run the extracted application jar (tools jarmode layout: application.jar + lib/)
# JAVA_TOOL_OPTIONS is automatically picked up by the JVM at startup
ENTRYPOINT ["java", "-jar", "application.jar"]

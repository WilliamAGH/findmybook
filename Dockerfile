# syntax=docker/dockerfile:1
##
## Multi-stage build for Java Spring Boot application (Gradle + Java 25)
## Base registry defaults to AWS ECR Public mirror but can be overridden
ARG BASE_REGISTRY=public.ecr.aws/docker/library

# ---------- Build stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk AS build
WORKDIR /app

# 0. Install Node.js for frontend build
# Exact official release matching frontend/package.json engines.node, checksum-verified
ARG NODE_VERSION=24.18.0
ARG TARGETARCH
RUN set -eux; \
    apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates xz-utils; \
    case "${TARGETARCH:-amd64}" in amd64) node_arch=x64 ;; arm64) node_arch=arm64 ;; *) echo "unsupported arch ${TARGETARCH}"; exit 1 ;; esac; \
    node_tarball="node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"; \
    cd /tmp; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${node_tarball}"; \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" | grep " ${node_tarball}\$" | sha256sum -c -; \
    tar -xJf "${node_tarball}" -C /usr/local --strip-components=1 --no-same-owner; \
    rm -f "${node_tarball}"; \
    rm -rf /var/lib/apt/lists/*; \
    test "$(node --version)" = "v${NODE_VERSION}"

# 1. Gradle wrapper & configuration (rarely changes)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x ./gradlew

# 2. Pre-fetch Gradle dependencies (layer cached until build scripts change)
RUN ./gradlew dependencies --no-daemon -q

# 3. Frontend dependency install (cached until the lockfile inputs change)
# The pnpm store and corepack cache mounts share their ids with every other fleet
# image; corepack's pnpm is per architecture, and this stage runs on TARGETARCH.
# Corepack reads packageManager from the working directory's manifest.
# node_modules outlives this RUN in the image layer while the store is only a cache mount,
# so the image install keeps a per-project virtual store instead of linking into the shared one.
ENV pnpm_config_virtual_store_type=project
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./frontend/
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    --mount=type=cache,id=corepack-${TARGETARCH},target=/root/.cache/node/corepack \
    corepack enable \
    && cd frontend \
    && pnpm install --frozen-lockfile

# 4. Frontend config and source files (surgical copies avoid node_modules)
COPY frontend/index.html ./frontend/
COPY frontend/vite.config.ts frontend/tsconfig.json frontend/svelte.config.js frontend/tailwind.config.js ./frontend/
COPY frontend/public ./frontend/public
COPY frontend/src ./frontend/src

# 5. Java sources
COPY src ./src

# 6. Build JAR (Frontend build is triggered via Gradle processResources task)
# -q reduces noise, -x test skips tests for faster build
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    --mount=type=cache,id=corepack-${TARGETARCH},target=/root/.cache/node/corepack \
    ./gradlew bootJar --no-daemon -q -x test

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
ENV MANAGEMENT_ENDPOINT_HEALTH_PROBES_ADD_ADDITIONAL_PATHS=true
EXPOSE 8095

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system appgroup \
    && adduser --system --ingroup appgroup appuser

# Copy the extracted layers individually for optimal Docker caching
COPY --from=extractor --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser

# Gate rolling updates on Spring's application-readiness lifecycle. This
# intentionally excludes external diagnostics such as S3 from deployment health.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \
    CMD curl --fail --silent --show-error --max-time 4 "http://127.0.0.1:${SERVER_PORT}/readyz" || exit 1

# Run the extracted application jar (tools jarmode layout: application.jar + lib/)
# JAVA_TOOL_OPTIONS is automatically picked up by the JVM at startup
ENTRYPOINT ["java", "-jar", "application.jar"]

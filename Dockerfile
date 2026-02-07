##
## Multi-stage build for Java Spring Boot application (Gradle + Java 25)
## Base registry defaults to AWS ECR Public mirror but can be overridden
ARG BASE_REGISTRY=public.ecr.aws/docker/library

# ---------- Build stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk AS build
WORKDIR /app

# 0. Install Node.js for frontend build
# Using NodeSource to get Node.js 22.x (required by Vite/Svelte)
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# 1. Gradle wrapper & configuration (rarely changes)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x ./gradlew

# 2. Pre-fetch dependencies (layer caching handles repeat builds)
# Only runs when build.gradle.kts or settings.gradle.kts changes
RUN ./gradlew dependencies --no-daemon -q

# 3. Frontend sources
# Copying BEFORE src to separate frontend and backend change triggers
COPY frontend ./frontend

# 4. Java sources
COPY src ./src

# 5. Build JAR (Frontend build is triggered via Gradle processResources task)
# -q reduces noise, -x test skips tests for faster build
RUN ./gradlew bootJar --no-daemon -q -x test

# ---------- Runtime stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre AS runtime
WORKDIR /app
ENV SERVER_PORT=8095
ENV JAVA_OPTS="--enable-preview -Dio.netty.noUnsafe=true"
EXPOSE 8095

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# Copy the built jar from the build stage
COPY --from=build --chown=appuser:appgroup /app/build/libs/findmybook-*.jar app.jar

USER appuser

# Run the application using JSON array for better signal handling
ENTRYPOINT ["java", "-enable-preview", "-Dio.netty.noUnsafe=true", "-jar", "app.jar"]
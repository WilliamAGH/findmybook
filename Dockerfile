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

# ---------- Extractor stage for layered JAR ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk AS extractor
WORKDIR /app
COPY --from=build /app/build/libs/findmybook-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---------- Runtime stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre AS runtime
WORKDIR /app
ENV SERVER_PORT=8095
ENV JAVA_OPTS="--enable-preview -XX:MaxRAMPercentage=75.0 -Dio.netty.noUnsafe=true"
EXPOSE 8095

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# Copy the extracted layers individually for optimal Docker caching
COPY --from=extractor --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser

# Run the application using JSON array for better signal handling
ENTRYPOINT ["java", "--enable-preview", "-XX:MaxRAMPercentage=75.0", "-Dio.netty.noUnsafe=true", "org.springframework.boot.loader.launch.JarLauncher"]
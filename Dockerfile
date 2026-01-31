##
## Multi-stage build for Java Spring Boot application (Gradle + Java 25)
## Base registry defaults to AWS ECR Public mirror but can be overridden
## Some alternatives:
##   - docker.io/library
##   - ghcr.io/eclipse-temurin
##   - icr.io/appcafe
ARG BASE_REGISTRY=public.ecr.aws/docker/library
##

# ---------- Build stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk AS build
WORKDIR /app

# 0. Install Node.js for frontend CSS build (Tailwind)
RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs npm \
    && rm -rf /var/lib/apt/lists/*

# 1. Gradle wrapper (rarely changes)
COPY gradlew .
COPY gradle gradle
RUN chmod +x ./gradlew

# 2. Build configuration (changes occasionally)
COPY build.gradle .
COPY settings.gradle .

# 3. Frontend sources (used by buildFrontendCss task)
COPY frontend ./frontend
RUN cd frontend && npm install

# 4. Pre-fetch dependencies (layer caching handles repeat builds)
RUN ./gradlew dependencies --no-daemon --quiet

# 5. Java sources (most frequent changes) - copy LAST for optimal caching
COPY src ./src

# 6. Build JAR
RUN ./gradlew bootJar --no-daemon -x test

# ---------- Runtime stage ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre AS runtime
WORKDIR /app
ENV SERVER_PORT=8095
ENV JAVA_OPTS="--enable-preview -Dio.netty.noUnsafe=true"
EXPOSE 8095

# Copy the built jar from the build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Run the application (SERVER_PORT env var automatically bound to server.port by Spring Boot)
ENTRYPOINT exec java $JAVA_OPTS -jar /app/app.jar

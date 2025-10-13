FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy pom.xml first for better layer caching
COPY pom.xml .
COPY docker/maven-settings.xml /usr/share/maven/ref/settings-docker.xml

ARG MAVEN_CLI_OPTS="-B -s /usr/share/maven/ref/settings-docker.xml \
  -Dmaven.wagon.http.retryHandler.count=8 \
  -Dmaven.wagon.http.retryHandler.class=standard \
  -Dmaven.wagon.http.retryHandler.requestSentEnabled=true"

RUN mvn ${MAVEN_CLI_OPTS} dependency:go-offline \
  || (sleep 10 && mvn ${MAVEN_CLI_OPTS} dependency:go-offline)

# Copy source code
COPY src/ /app/src/

# Build the application
RUN mvn ${MAVEN_CLI_OPTS} package -DskipTests

# Use JRE for smaller runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ENV SERVER_PORT=8095
EXPOSE 8095

# Copy the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar

# Run the application (SERVER_PORT env var automatically bound to server.port by Spring Boot)
ENTRYPOINT ["java", "-jar", "app.jar"]

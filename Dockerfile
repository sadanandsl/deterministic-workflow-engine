# Build stage
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY orchestrator-core/pom.xml orchestrator-core/
COPY orchestrator-api/pom.xml orchestrator-api/
COPY orchestrator-engine/pom.xml orchestrator-engine/
COPY orchestrator-worker/pom.xml orchestrator-worker/
COPY orchestrator-scheduler/pom.xml orchestrator-scheduler/
COPY orchestrator-recovery/pom.xml orchestrator-recovery/
COPY orchestrator-advisory/pom.xml orchestrator-advisory/

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source and build
COPY . .
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -g 1000 orchestrator && \
    adduser -u 1000 -G orchestrator -s /bin/sh -D orchestrator

# Copy JAR
COPY --from=build /app/orchestrator-api/target/*.jar app.jar

# Set ownership
RUN chown -R orchestrator:orchestrator /app
USER orchestrator

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

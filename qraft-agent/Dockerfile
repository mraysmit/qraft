# Multi-stage build for Qraft Agent
FROM maven:3.9.9-eclipse-temurin-25 AS builder

# Set working directory
WORKDIR /app

# Copy parent POM and module POMs first for better layer caching
COPY pom.xml .
COPY qraft-core/pom.xml qraft-core/
COPY qraft-workflow/pom.xml qraft-workflow/
COPY qraft-agent/pom.xml qraft-agent/

# Download dependencies
RUN mvn dependency:go-offline -pl qraft-core,qraft-workflow,qraft-agent -am

# Copy source code
COPY qraft-core/src qraft-core/src
COPY qraft-workflow/src qraft-workflow/src
COPY qraft-agent/src qraft-agent/src

# Build the agent and dependencies
RUN mvn clean package -pl qraft-agent -am -DskipTests

# ============================================================================
# Runtime stage
FROM eclipse-temurin:25-jre-alpine

# Install required packages
RUN apk add --no-cache \
    curl \
    bash \
    tzdata \
    && rm -rf /var/cache/apk/*

# Create qraft user
RUN addgroup -g 1001 qraft && \
    adduser -D -u 1001 -G qraft qraft

# Set working directory
WORKDIR /app

# Copy the built JAR and dependencies
COPY --from=builder /app/qraft-agent/target/qraft-agent-*.jar app.jar
COPY --from=builder /app/qraft-agent/target/lib/ lib/

# Copy configuration files from resources
COPY qraft-agent/src/main/resources/logback.xml config/logback.xml

# Create directories for logs and data
RUN mkdir -p /app/logs /app/data /app/transfers && \
    chown -R qraft:qraft /app

# Switch to non-root user
USER qraft

# Expose agent communication port
EXPOSE 8080

# Environment variables for Qraft Agent
ENV JAVA_OPTS="-Xmx256m -Xms128m" \
    AGENT_ID="" \
    AGENT_REGION="default" \
    AGENT_DATACENTER="default" \
    CONTROLLER_URL="http://localhost:8080/api/v1" \
    SUPPORTED_PROTOCOLS="HTTP,HTTPS" \
    MAX_CONCURRENT_TRANSFERS=5 \
    HEARTBEAT_INTERVAL=30000 \
    AGENT_PORT=8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${AGENT_PORT}/health || exit 1

# Entry point
COPY qraft-agent/docker-entrypoint.sh /docker-entrypoint.sh
USER root
RUN chmod +x /docker-entrypoint.sh
USER qraft

ENTRYPOINT ["/docker-entrypoint.sh"]

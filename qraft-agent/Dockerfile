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

# Package the unified runtime JAR built by Maven on the host.
COPY qraft-runtime/target/qraft-runtime.jar app.jar

# Copy configuration files from resources
COPY qraft-agent/src/main/resources/logback.xml config/logback.xml

# Create directories for logs and data
RUN mkdir -p /app/logs /app/data && \
    chown -R qraft:qraft /app

# Switch to non-root user
USER qraft

# Expose agent communication port
EXPOSE 8080

# Environment variables for Qraft Agent
ENV JAVA_OPTS="-Xmx256m -Xms128m" \
    QRAFT_MODE="client" \
    AGENT_ID="" \
    AGENT_REGION="default" \
    AGENT_DATACENTER="default" \
    CONTROLLER_URL="http://localhost:8080/api/v1" \
    SUPPORTED_PROTOCOLS="HTTP,HTTPS" \
    HEARTBEAT_INTERVAL=30000 \
    AGENT_PORT=8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${AGENT_PORT}/health || exit 1

# Entry point
COPY qraft-agent/docker-entrypoint.sh /docker-entrypoint.sh
USER root
RUN sed -i 's/\r$//' /docker-entrypoint.sh \
    && chmod +x /docker-entrypoint.sh
USER qraft

ENTRYPOINT ["/docker-entrypoint.sh"]

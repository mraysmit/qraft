FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl tzdata bash \
    && addgroup -g 1001 qraft \
    && adduser -D -u 1001 -G qraft qraft

WORKDIR /app
COPY qraft-runtime/target/qraft-runtime.jar /app/qraft.jar
COPY qraft-runtime/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
RUN mkdir -p /app/logs /app/data \
    && chown -R qraft:qraft /app

USER qraft
ENV JAVA_OPTS="-Xmx512m -Xms128m" \
    QRAFT_MODE="" \
    AGENT_ID="" \
    CONTROLLER_URL="http://localhost:8080/api/v1"

EXPOSE 8080 9080 10080

ENTRYPOINT ["/docker-entrypoint.sh"]

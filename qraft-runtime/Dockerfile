FROM sapmachine:27-jre-alpine

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
EXPOSE 8080 9080 10080

ENTRYPOINT ["/docker-entrypoint.sh"]

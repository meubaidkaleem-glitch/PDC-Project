# DistriCompute images (docs/deployment.md). They package the jars that scripts/build.sh produces, so the image
# contains exactly what the tests ran against:
#
#   scripts/build.sh
#   docker compose build
#
# Target "app": the backend with the interface and the embedded coordinator.
# Target "worker": a DistriCompute-Distributed worker process.

# eclipse-temurin:21-jre (Ubuntu 26.04), pinned by digest
FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d AS base
# One unprivileged user for both images, so that workers can read the cluster secret the application writes (owner
# only) on the shared volume.
RUN groupadd --system --gid 10001 districompute \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data --no-create-home districompute \
    && mkdir -p /app /data && chown districompute:districompute /data

FROM base AS app
COPY backend/target/districompute-backend-*.jar /app/districompute.jar
USER districompute
WORKDIR /data
EXPOSE 8080 7650
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/districompute.jar"]
CMD ["--districompute.data-dir=/data"]

FROM base AS worker
COPY components/distributed/distributed/target/districompute-distributed-*-all.jar /app/worker.jar
USER districompute
WORKDIR /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/worker.jar", "worker"]

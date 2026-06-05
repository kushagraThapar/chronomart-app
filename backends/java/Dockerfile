# Multi-stage build: build with full JDK, ship with JRE-only.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Pre-fetch dependencies — copy pom first so a code-only change reuses this layer.
COPY pom.xml .
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/chronomart-java-backend-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar

# JVM defaults sized for an emulator-scale workload; override via JAVA_TOOL_OPTIONS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8101

EXPOSE 8101

HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=30s \
    CMD wget -q -O /dev/null http://127.0.0.1:8101/healthz || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

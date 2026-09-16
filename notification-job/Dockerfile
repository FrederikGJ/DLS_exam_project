# notification-job (dev plan DP-20): a run-to-completion program, not a server - no port, no health check.
# The container exits with 0 when the queue is drained (see README.md).

# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached between source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/notification-job.jar /build/app.jar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# apk upgrade: the base image lags behind Alpine's security fixes (openssl, expat ...); Trivy scans the result in CI.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
# Short-lived JVM: C1 only and the serial GC start fastest and need the least memory for a few hundred messages.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

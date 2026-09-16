# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached between source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/*.jar /build/app.jar

# ---- CDS stage: extract the jar and record a class data sharing archive (dev plan DP-14) ---------------------
# Spring Boot's recommended setup: the extracted layout (app.jar + lib/) is faster to start than the fat jar, and
# a training run that starts the application context once writes every loaded class to app.jsa. At runtime the
# JVM maps the archive instead of loading and verifying those classes again. Measured in k8s/README.md
# ("Class Data Sharing"): boot under the pod's CPU limit ~21 s -> ~12 s, ~37 MiB less memory.
#
# The training run needs no database or broker: spring.context.exit=onRefresh stops right after the context has
# been refreshed (before listeners, schedulers and the web server start), Flyway is off, and Hibernate is told the
# dialect instead of asking the database. The JVM flags must be the ones the pods and compose use
# (JAVA_TOOL_OPTIONS), otherwise the archive can be rejected at runtime.
FROM eclipse-temurin:21-jre-alpine AS cds
WORKDIR /cds
COPY --from=build /build/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application
WORKDIR /cds/application
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k"
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -jar app.jar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# apk upgrade: the base image lags behind Alpine's security fixes (openssl, expat ...); Trivy scans the result in CI.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=cds /cds/application /app
USER app
EXPOSE 8080
# If the archive does not match the JVM (other flags, other JDK build) the JVM ignores it and starts normally,
# just without the speed-up - it never fails because of CDS.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]

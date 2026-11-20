# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ---------------------------------------------------------
# No HTTP port: the container runs once per message (KEDA ScaledJob) and exits.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/notification-job.jar app.jar
USER app
# Short-lived process: small serial GC and C1 only start faster than the defaults
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto"
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["once"]

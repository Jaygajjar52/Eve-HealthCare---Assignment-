# ---------------------------------------------------------------------------
# Stage 1: build the jar with Maven.
# The dependency layer is copied and downloaded on its own so that editing
# source code does not re-download the whole internet on every rebuild.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2: ship only a JRE and the built jar. No Maven, no sources in the
# final image, and the app runs as a non-root user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app

RUN groupadd --system eve \
    && useradd --system --gid eve --no-create-home eve

COPY --from=build --chown=eve:eve /build/target/diagnostics-service-*.jar app.jar

USER eve

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
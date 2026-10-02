# ---------------------------------------------------------------------------
# Stage 1: Build the JAR with Maven
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy dependency definition first for Docker layer caching
COPY pom.xml .

RUN mvn -B dependency:go-offline

# Copy source code
COPY src ./src

# Build application
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2: Runtime image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root user
RUN groupadd --system eve \
    && useradd --system --gid eve --no-create-home eve

# Copy JAR
COPY --from=build --chown=eve:eve /build/target/diagnostics-service-*.jar app.jar

USER eve

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
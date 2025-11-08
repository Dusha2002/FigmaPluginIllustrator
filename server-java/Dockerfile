# syntax=docker/dockerfile:1

# Build stage: compile Spring Boot application using Maven Wrapper
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /workspace

# Copy Maven wrapper and pom first to leverage layer caching
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./

# Pre-download dependencies (no source yet)
RUN ./mvnw -B dependency:go-offline

# Copy remaining sources
COPY src src

# Build the executable JAR (tests skipped, assumed already covered in CI)
RUN ./mvnw -B clean package -DskipTests

# Runtime stage: slim JRE with built application
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=builder /workspace/target/export-server-*.jar app.jar

# Railway provides PORT env variable; default to 8080 for local usage
ENV PORT=8080
ENV JAVA_OPTS="-Djava.awt.headless=true"

EXPOSE 8080

# Ensure Spring Boot listens on the PORT assigned by Railway
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT}"]

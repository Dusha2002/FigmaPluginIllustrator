# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# Copy Maven wrapper and project descriptor for dependency resolution
COPY server-java/mvnw ./mvnw
COPY server-java/mvnw.cmd ./mvnw.cmd
COPY server-java/.mvn .mvn
COPY server-java/pom.xml ./pom.xml

RUN ./mvnw -B dependency:go-offline

# Copy sources of the Spring Boot service
COPY server-java/src ./src

RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /workspace/target/export-server-*.jar app.jar

ENV PORT=8080
ENV JAVA_OPTS="-Djava.awt.headless=true"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT}"]

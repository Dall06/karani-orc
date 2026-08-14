# Stage 1: Build the application using Gradle and JDK 21
FROM gradle:8.5-jdk21-alpine AS builder

WORKDIR /home/gradle/project

# Copy Gradle configurations to leverage Docker cache for dependencies
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/

# Build dependencies only (caches dependencies)
RUN gradle build -x test --no-daemon || true

# Copy project source
COPY src/ ./src/

# Install the application distribution (lightweight, native Ktor dist)
RUN gradle installDist --no-daemon

# Stage 2: Minimal runtime image using Eclipse Temurin JRE 21 (Alpine for security)
FROM eclipse-temurin:21-jre-alpine

# Set security configurations & non-root user
RUN addgroup -S karani && adduser -S karani -G karani
WORKDIR /app

# Copy built distribution from Stage 1
COPY --from=builder --chown=karani:karani /home/gradle/project/build/install/karani-orc /app

# Copy default application configuration
COPY --chown=karani:karani application.yaml /app/application.yaml

# Use non-root user for execution
USER karani

# Expose default HTTP Ktor port
EXPOSE 8080

# Run the Ktor application using its startup script
ENTRYPOINT ["/app/bin/karani-orc"]

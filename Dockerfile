# Stage 1: Build Kotlin application
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew jar --no-daemon

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install printer tools (CUPS client for driverless IPP/AirPrint), Python & Pillow
RUN apt-get update && apt-get install -y --no-install-recommends \
    cups-client \
    ipp-usb \
    python3 \
    python3-pil \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Antigravity CLI (agy) via official bootstrapper script into /usr/local/bin
RUN curl -fsSL https://antigravity.google/cli/install.sh | bash -s -- -d /usr/local/bin \
    && chmod 755 /usr/local/bin/agy

# Create non-root user (appuser:appuser with UID/GID 1000)
RUN groupadd -g 1000 appuser && \
    useradd -u 1000 -g appuser -m -s /bin/bash appuser

# Copy built application JAR
COPY --from=builder /build/build/libs/colorich-1.0.0.jar /app/colorich.jar

# Copy skills so agy has access to photo-to-coloring-page workflow
COPY .agents /app/.agents

# Copy entrypoint script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Change ownership of /app to appuser
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser
ENV HOME=/home/appuser
ENV PATH="/home/appuser/.local/bin:/usr/local/bin:${PATH}"

# Environment defaults
ENV POLL_INTERVAL_SECONDS=60
ENV CUPS_SERVER=/run/cups/cups.sock
ENV DRY_RUN=false

ENTRYPOINT ["/app/entrypoint.sh"]

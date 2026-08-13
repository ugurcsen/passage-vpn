# ---- Build stage: compile backend jar ----
# Context is the repository root (see docker-compose.yml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY backend/gradle/ gradle/
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY backend/src src
RUN ./gradlew --no-daemon bootJar

# ---- Runtime stage: JRE + easy-rsa + openvpn (PKI authority) ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
        easy-rsa \
        openvpn \
        openssl \
        python3 \
        bash \
        curl \
        ca-certificates \
    && ln -s /usr/share/easy-rsa/easyrsa /usr/local/bin/easyrsa \
    && rm -rf /var/lib/apt/lists/*

ENV OPNL_DATA_DIR=/var/lib/opnl \
    OPNL_PKI_DIR=/etc/opnl/pki \
    OPNL_CCD_DIR=/etc/opnl/ccd \
    OPNL_CONFIG_DIR=/etc/opnl/config \
    OPNL_SCRIPTS_SRC_DIR=/app/openvpn-scripts
RUN mkdir -p "$OPNL_DATA_DIR" "$OPNL_PKI_DIR" "$OPNL_CCD_DIR" "$OPNL_CONFIG_DIR"

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
# Template helper scripts (rendered with the internal token at startup).
COPY openvpn/scripts/ /app/openvpn-scripts/

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

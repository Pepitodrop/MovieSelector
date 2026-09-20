# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY piet-core ./piet-core
COPY game-engine ./game-engine
COPY backend ./backend
COPY web ./web
RUN ./gradlew --no-daemon :backend:test :game-engine:test :piet-core:test \
    :backend:installDist :web:browserDistribution

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin movieselector
WORKDIR /app
COPY --from=builder --chown=movieselector:movieselector /src/backend/build/install/backend /app/backend
COPY --from=builder --chown=movieselector:movieselector /src/web/build/dist/js/productionExecutable /app/site
ENV SITE_DIRECTORY=/app/site \
    PORT=8080 \
    PUBLIC_ORIGIN=https://game.luisbenedikt.de \
    WENCKE_BASE_URL=https://wencke.love
EXPOSE 8080
USER movieselector
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl --fail --silent "http://localhost:$PORT/api/health" || exit 1
ENTRYPOINT ["/app/backend/bin/backend"]

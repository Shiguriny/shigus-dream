# Сборка backend в Docker (используется docker-compose и Render Blueprint).
# Контекст сборки — корень репозитория (render.yaml: dockerContext: ./),
# чтобы внутрь попал VERSION (единый источник версии).
FROM gradle:9.5.1-jdk25 AS build
WORKDIR /app
COPY backend/build.gradle backend/settings.gradle backend/gradle.properties ./
COPY backend/gradle ./gradle
COPY backend/src ./src
COPY VERSION /VERSION
RUN gradle installDist --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/install/backend ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]

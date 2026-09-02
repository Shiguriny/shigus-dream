# Сборка backend в Docker (используется docker-compose).
FROM gradle:9.5.1-jdk25 AS build
WORKDIR /app
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/install/backend ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]

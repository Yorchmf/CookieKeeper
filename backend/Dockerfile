# Complyr backend API image.
# Build context: backend/ — e.g. `docker build -t complyr-api -f backend/Dockerfile backend/`
# Tests run in CI (Testcontainers); the image build only compiles and packages.

FROM gradle:jdk21 AS build
WORKDIR /workspace

# Warm the dependency cache first so source changes don't re-resolve everything.
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN gradle dependencies --no-daemon --quiet > /dev/null 2>&1 || true

COPY . .
RUN gradle bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system complyr && useradd --system --gid complyr --uid 1001 complyr

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

USER complyr
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

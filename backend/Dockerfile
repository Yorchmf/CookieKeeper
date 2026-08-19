# CookieKeeper backend API image.
# Build context: backend/ — e.g. `docker build -t cookiekeeper-api -f backend/Dockerfile backend/`
# Tests run in CI (Testcontainers); the image build only compiles and packages.

FROM gradle:jdk21 AS build
WORKDIR /workspace

# The repo-root VERSION file is outside this build context, so the release workflows pass it in.
# The default keeps a bare `docker build` working for local experiments.
ARG APP_VERSION=0.0.0-SNAPSHOT

# Warm the dependency cache first so source changes don't re-resolve everything.
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN gradle dependencies --no-daemon --quiet > /dev/null 2>&1 || true

COPY . .
RUN gradle bootJar -x test --no-daemon -PappVersion="$APP_VERSION"

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system cookiekeeper && useradd --system --gid cookiekeeper --uid 1001 cookiekeeper

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

USER cookiekeeper
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

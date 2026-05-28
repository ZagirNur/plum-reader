# Multi-stage build for the Plum Reader Kotlin/Spring Boot server.
#
# Stage 1 (build): gradle wrapper from the repo + JDK 21 → bootJar.
# Stage 2 (runtime): eclipse-temurin 21-jre-jammy + the fat jar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Bring the wrapper and config first so dependency downloads can be cached.
COPY server/gradlew server/gradlew
COPY server/gradle server/gradle
COPY server/settings.gradle.kts server/settings.gradle.kts
COPY server/build.gradle.kts server/build.gradle.kts

# Prime the gradle/dependency cache. `--no-daemon` is mandatory in containers.
RUN cd server && ./gradlew --no-daemon --version

# Now the sources.
COPY server/src server/src

RUN cd server && ./gradlew --no-daemon bootJar -x test \
 && ls -l build/libs

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user.
RUN useradd -r -u 1001 -m -d /app plum && chown -R plum:plum /app
USER plum

COPY --from=build --chown=plum:plum /workspace/server/build/libs/*.jar /app/plum-reader.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/plum-reader.jar"]

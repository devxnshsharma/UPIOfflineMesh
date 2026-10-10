# Container for a hosted demo (Render uses this file). The dashboard is inside the jar, so one process serves everything.
# Build: Maven + JDK 17 compile and package. Run: a JRE 17 image as an unprivileged user.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -q clean package -DskipTests

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
# pom.xml puts the build output in target.nosync (a folder macOS iCloud ignores); it is just a folder name here.
COPY --from=build /src/target.nosync/upi-offline-mesh-1.0.0.jar app.jar
USER app

# hosted = admin token for reset/settings/experiments, rate limits, no database console (see application-hosted.properties).
# The memory flags keep the JVM inside a 512 MB instance. Render sets PORT; the app listens on it.
ENV SPRING_PROFILES_ACTIVE=hosted \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m"
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

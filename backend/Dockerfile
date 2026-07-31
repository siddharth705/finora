# Multi-stage build: compile with Maven, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Production-readiness pass: this image used to run as root (Alpine JRE images default to root
# if nothing says otherwise) — a container compromise via any dependency vulnerability then has
# root inside the container for free, no privilege escalation needed. A dedicated, unprivileged
# user costs nothing functionally (the app doesn't write anywhere in the container that isn't
# already writable) and is standard container-security hygiene for anything handling real user
# financial data.
RUN addgroup -S finora && adduser -S finora -G finora
COPY --from=build /app/target/finora-backend-*.jar app.jar
RUN chown finora:finora app.jar
USER finora

# EXPOSE is documentation only -- it doesn't control which port the app actually binds to (that's
# server.port / $PORT, see application.yml's own comment) or affect Railway's own port detection,
# which reads $PORT directly. 8080 is simply the local/Docker default when $PORT is unset.
EXPOSE 8080

# -XX:MaxRAMPercentage (rather than a fixed -Xmx) lets the JVM heap scale with whatever container
# memory limit the deployment actually has (Railway plan size, a Docker --memory flag, etc.)
# instead of a number picked for one specific environment. 75% leaves headroom for
# non-heap JVM overhead (metaspace, thread stacks, direct buffers -- PDFBox/PdfPreviewGenerator's
# PDF parsing uses some of this) without wasting most of a small container's memory the way the
# JVM's own default (25%) would on a single-application container like this one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

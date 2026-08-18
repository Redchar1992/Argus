# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-17 AS build

ARG MODULE
WORKDIR /workspace

COPY pom.xml ./
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY agent-orchestrator-service/pom.xml agent-orchestrator-service/pom.xml
COPY screening-tools-service/pom.xml screening-tools-service/pom.xml
COPY case-service/pom.xml case-service/pom.xml

COPY . .
RUN --mount=type=cache,target=/root/.m2 test -n "${MODULE}" \
    && mvn -B -q -pl "${MODULE}" -am -DskipTests package \
    && find "${MODULE}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' \
       -exec cp '{}' /tmp/app.jar \; \
    && test -s /tmp/app.jar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 argus \
    && useradd --system --uid 10001 --gid argus --home-dir /app --shell /usr/sbin/nologin argus

WORKDIR /app
COPY --from=build --chown=argus:argus /tmp/app.jar /app/app.jar
USER 10001:10001

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

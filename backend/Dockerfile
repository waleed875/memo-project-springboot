# Preview-only backend image. Frontend and business endpoints are NOT migrated yet.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY docker/HealthProbe.java /tmp/HealthProbe.java
RUN mvn -B -ntp test package && \
    mkdir -p /tmp/probe && javac -d /tmp/probe /tmp/HealthProbe.java

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system --gid 10001 memo && \
    useradd --system --uid 10001 --gid memo --no-create-home --shell /usr/sbin/nologin memo
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/memo-api-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build --chown=10001:10001 /tmp/probe/ /opt/probe/
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["java", "-cp", "/opt/probe", "HealthProbe"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

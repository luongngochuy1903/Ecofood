# --- New Relic Java agent (version must match the pom.xml dependency, 9.5.0) ---
FROM alpine:3.20 AS newrelic
RUN apk add --no-cache curl unzip \
    && curl -sSL -o /tmp/newrelic.zip \
       https://download.newrelic.com/newrelic/java-agent/newrelic-agent/9.5.0/newrelic-java-9.5.0.zip \
    && unzip -q /tmp/newrelic.zip -d /opt \
    && rm /tmp/newrelic.zip

# --- Runtime ---
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=newrelic /opt/newrelic /app/newrelic
COPY newrelic/newrelic.yml /app/newrelic/newrelic.yml
COPY target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-javaagent:/app/newrelic/newrelic.jar", "-jar", "app.jar"]

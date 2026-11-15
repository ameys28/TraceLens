# ---- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# OpenTelemetry Java agent, fetched from Maven Central (more reliable than GitHub release downloads)
RUN mvn -B -q dependency:copy \
      -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.10.0 \
      -DoutputDirectory=/agent -Dmdep.stripVersion=true
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q package -DskipTests

# ---- run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /agent/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /src/target/demo-services.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

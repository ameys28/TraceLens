# ---- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q package -DskipTests

# ---- run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/tracelens-platform.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

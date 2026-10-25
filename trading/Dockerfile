FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Unprivileged runtime user — the service writes nothing to disk (state lives in Postgres).
RUN useradd --system --uid 10001 --no-create-home trading
USER trading
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

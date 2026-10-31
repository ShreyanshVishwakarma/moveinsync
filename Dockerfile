# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# POMs first so dependency resolution is cached independently of source changes.
COPY pom.xml .
COPY config-sdk/pom.xml config-sdk/
COPY config-server/pom.xml config-server/
RUN mvn -q -B -pl config-server -am -DskipTests dependency:go-offline

COPY config-sdk/src config-sdk/src
COPY config-server/src config-server/src
RUN mvn -q -B -pl config-server -am -DskipTests package

# --- runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/config-server/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

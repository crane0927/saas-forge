ARG MODULE

FROM maven:3.9.14-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -pl "${MODULE}" -am package -DskipTests

FROM eclipse-temurin:17-jre
ARG MODULE
RUN groupadd --system spring && useradd --system --gid spring --create-home --home-dir /app spring
WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/${MODULE}/target/*.jar application.jar
USER spring
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

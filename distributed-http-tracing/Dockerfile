FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY order-service ./order-service
COPY payment-service ./payment-service
ARG SERVICE
RUN sh ./gradlew --no-daemon :${SERVICE}:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG SERVICE
COPY --from=build /app/${SERVICE}/build/libs/${SERVICE}-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

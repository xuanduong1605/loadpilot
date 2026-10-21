FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 platform && useradd --uid 10001 --gid 10001 --no-create-home platform
WORKDIR /app
COPY --from=build /build/target/platform-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx384m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

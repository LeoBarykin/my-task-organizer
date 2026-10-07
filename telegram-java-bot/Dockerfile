FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/telegram-java-bot-1.0.0.jar app.jar
ENV TELEGRAM_BOT_TOKEN=""
ENV TASKS_DB_PATH="/data/family-tasks.db"
VOLUME ["/data"]
CMD ["java", "-jar", "app.jar"]

FROM gradle:8.11-jdk21 AS build

WORKDIR /home/gradle/src

COPY . .

RUN chown -R gradle:gradle ./ && gradle build --no-daemon

FROM eclipse-temurin:21.0.5_11-jre

RUN useradd java

WORKDIR /app

COPY --from=build /home/gradle/src/build/libs/rpgc-bot.jar ./

RUN chown -R java:java /app

USER java

ENTRYPOINT ["java", "-jar", "rpgc-bot.jar"]
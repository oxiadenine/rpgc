FROM eclipse-temurin:21.0.6_7-jre-alpine-3.21

ARG ugid

ENV UGID=${ugid:-1000}

RUN addgroup -g $UGID rpgc && adduser -u $UGID -D rpgc -G rpgc

WORKDIR /home/rpgc

COPY build/libs/rpgc-bot.jar .

RUN chown -R $UGID:$UGID rpgc-bot.jar

USER $UGID:$UGID

EXPOSE 8000

CMD ["java", "-jar", "rpgc-bot.jar"]
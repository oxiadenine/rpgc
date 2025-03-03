FROM eclipse-temurin:21.0.6_7-jre-alpine-3.21

ARG user

ENV USER=${user:-root}

WORKDIR /rpgc-bot

COPY build/libs/rpgc-bot.jar .

RUN if [ $USER != "root" ]; then addgroup -g $USER rpgc; fi
RUN if [ $USER != "root" ]; then adduser -u $USER -D rpgc -G rpgc; fi

RUN chown -R $USER:$USER /rpgc-bot

USER $USER:$USER

EXPOSE 8000

CMD ["java", "-jar", "rpgc-bot.jar"]
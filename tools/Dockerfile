FROM eclipse-temurin:21.0.6_7-jre-alpine-3.21

ARG ugid

ENV UGID=${ugid:-0}

RUN if [ $UGID != 0 ]; then addgroup -g $UGID rpgc; fi
RUN if [ $UGID != 0 ]; then adduser -u $UGID -D rpgc -G rpgc; fi

WORKDIR /home/rpgc

COPY build/downloads/h2.jar .

RUN chown $UGID:$UGID h2.jar

USER $UGID:$UGID

CMD ["/bin/sh"]
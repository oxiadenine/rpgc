FROM eclipse-temurin:21.0.6_7-jre-alpine-3.21

ARG ugid

ENV UGID=${ugid:-1000}

RUN addgroup -g $UGID rpgc && adduser -u $UGID -D rpgc -G rpgc

WORKDIR /home/rpgc

COPY build/downloads/h2.jar .

RUN chown $UGID:$UGID h2.jar

USER $UGID:$UGID

CMD ["/bin/sh"]
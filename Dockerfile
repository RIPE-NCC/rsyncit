FROM eclipse-temurin:25.0.4_7-jdk-alpine-3.23@sha256:b7c88ce22d575642650ec83cbf4e470a0c183a46871467180238e4b27ad9e20a

VOLUME /data
VOLUME /conf

RUN apk add --no-cache rsync

ARG JAR_FILE
COPY ${JAR_FILE} /rsyncit.jar

COPY docker/run.sh run.sh

RUN chmod +x /run.sh
RUN mkdir -p /var/log/rsyncd/

EXPOSE 8080
EXPOSE 873
EXPOSE 5005

CMD [ "/run.sh" ]

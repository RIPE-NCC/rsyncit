FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:d3f9f60ad2040582239e2977ee753d598787d8b064ca39a8e131860165dd81fb

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

FROM eclipse-temurin:25-jdk-alpine-3.23@sha256:da683f4f02f9427597d8fa162b73b8222fe08596dcebaf23e4399576ff8b037e

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

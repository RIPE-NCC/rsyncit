FROM eclipse-temurin:25.0.5_8-jdk-alpine-3.23@sha256:9c835a4ba215132c44248947376e270323ccca5a770248dd5cdf6c1711ebfa1f

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

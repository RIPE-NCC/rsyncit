FROM eclipse-temurin:25.0.5_8-jdk-alpine-3.23@sha256:b903bc22d49da99a0af3d50cf08f6056eaf39646b71bf3a51f74720f8243b34b

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

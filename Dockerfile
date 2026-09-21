FROM eclipse-temurin:25.0.4_7-jdk-alpine-3.23@sha256:4e1fe101898bbb83e4e6572e048b3f2442669ae0bb54f3693d840eed44672c0f

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

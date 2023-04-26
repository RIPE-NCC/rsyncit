FROM eclipse-temurin:17-jdk-alpine
VOLUME /data
RUN apk update && apk add --no-cache rsync
ARG JAR_FILE
COPY ${JAR_FILE} rsyncit.jar
COPY docker/run.sh run.sh
RUN chmod +x /run.sh
RUN mkdir -p /var/log/rsyncd/
COPY docker/rsyncd.conf.docker /etc/rsyncd.conf

EXPOSE 8080
EXPOSE 873
EXPOSE 5005

CMD [ "/run.sh" ]
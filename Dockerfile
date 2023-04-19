FROM alpine:3.16 as build

ARG RSYNCIT_JAR=build/libs/rsyncit-0.0.1-SNAPSHOT.jar

RUN mkdir -p /data/rsync

ADD . /app
COPY ${RSYNCIT_JAR} /app/rsyncit.jar

WORKDIR /app

# use gcr.io/distroless/java-debian10:11-debug if you want to be able to run a
# shell in the container (e.g. `docker run -it --entrypoint sh --rm <image>`)
FROM gcr.io/distroless/java-debian10:11
LABEL org.label-schema.vcs-ref="unknown"

EXPOSE 8080

VOLUME ["/data"]

ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true \
    -Djava.net.preferIPv4Addresses=true \
    -Dapp.name=rsyncit \
    -Xms1g \
    -Xmx2g \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data/dumps/pubserver-heap-dump.hprof \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
    -DrsyncPath=/data/rsync \
    -DrrdpUrl=https://rrdp.ripe.net/notification.xml"

CMD ["/app/rsyncit.jar"]

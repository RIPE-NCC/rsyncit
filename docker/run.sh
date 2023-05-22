#!/bin/sh

if [ -z "${NO_RSYNCD}" ]; then
  echo "Running rsync..."
  rsync --config=/conf/rsyncd.conf --daemon &
  echo "Rsync started"
else
  echo "NO_RSYNC is set, not running rsyncd"
fi

echo "Running rsyncit..."
java -Djava.net.preferIPv4Stack=true \
    -Djava.net.preferIPv4Addresses=true \
    -Dapp.name=rsyncit \
    -Xms1g \
    -Xmx3g \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data/dumps/rsync.hprof \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
    -DrsyncPath=/data \
    -jar /rsyncit.jar


#!/bin/sh

if [ -z "${NO_RSYNCD}" ]; then
  echo "Running rsync..."
  rsync --config=/conf/rsyncd.conf --daemon &
  echo "Rsync started"
else
  echo "NO_RSYNC is set, not running rsyncd"
fi

echo "Running rsyncit..."
java -Dapp.name=rsyncit \
    -Xms2g \
    -Xmx2g \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data/rsync.hprof \
    -DrsyncPath=/data \
    -jar /rsyncit.jar


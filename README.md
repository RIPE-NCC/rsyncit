# rsyncit

RRDP -> rsync simple tool

# Running it

At the moment config file is intentionally left out and configuration is set using CLI options or ENV
These are working values for running sync job every 10 minutes
```-
-DrrdpUrl=https://rrdp.ripe.net/notification.xml -DrsyncPath=/tmp -Dcron="0 0/10 * * * ?" -DrequestTimeout=PT120S
```
    

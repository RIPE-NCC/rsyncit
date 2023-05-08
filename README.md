# rsyncit

RRDP -> rsync simple tool

# Running locally

At the moment config file is intentionally left out and configuration is set using CLI options or ENV
These are working values for running sync job every 10 minutes
```-
-DrrdpUrl=https://rrdp.ripe.net/notification.xml -DrsyncPath=/tmp 
```
There are other parameters in `AppConfig` class, but they have reasonable defaults and not necessary for testing.
    
# Running as a Docker container

* Building image
    
    ```
    docker build \ 
        --build-arg JAR_FILE=build/libs/rsyncit-0.0.1-SNAPSHOT.jar \ 
        -t rsyncit/0.0.1 .
    ```
    
* Running example:
    
  ```
    docker run -p 8080:8080 -p 8730:873 \
        -v path-to-conf-dir:/conf
        --env RRDPURL="https://rrdp.ripe.net/notification.xml" \
        rsyncit/0.0.`
    ```
  
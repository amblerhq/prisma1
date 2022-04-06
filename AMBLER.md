# Install JDK
Install SDKMan: https://sdkman.io/install
Install Java8: `sdk install java 8.0.322-zulu`

# Build docker image
Go to server subdirectory `cd server`
Install dependencies `sbt update`
Build `COMMIT_SHA="local" CLUSTER_VERSION="1.34.11-ambler" sbt docker`

There's an error `[error] java.lang.RuntimeException: Could not parse image id`
But nevermind

# Run the build image
List new images `docker images`
There are 2 images one for local and another one for production.
You have to use production build.
Pick an image id and use it in our `docker-compose.yml` instead of ambler/prisma-aarch64
Launch with `docker-compose up`

If you see some errors about RabbitMQ: you chosen the wrong image, choose the other one.

# Debug
Add at the same level thant `PRISMA_CONFIG` under `environment`:
```
      LOG_LEVEL: "DEBUG"
      JAVA_OPTS: "-Dcom.sun.management.jmxremote=true -Dcom.sun.management.jmxremote.port=1099 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false"
```

It will increase the log verbosity and expose a JMX interface.
To see the metrics into JMX interface, launch an interactive bash in the container, then;
```
# apt-get update && apt-get install -y wget
# wget https://github.com/jiaqi/jmxterm/releases/download/v1.0.1/jmxterm-1.0.1-uber.jar
# java -jar jmxterm-1.0.1-uber.jar
$>open localhost:1099
$>beans
$>get -b slick:name=graphdb,type=AsyncExecutor *
```

You can see an output like:
```
#mbean = slick:name=graphdb,type=AsyncExecutor:
MaxQueueSize = 1000;

QueueSize = 0;

MaxThreads = 1;

ActiveThreads = 0;
```
# Building Eclair

## Requirements
- [Java Development Kit](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) 1.8
- [Maven](https://maven.apache.org/download.cgi) 3.3.x
- [Inno Setup](http://www.jrsoftware.org/isdl.php) 5.5.9 (optional, if you want to generate the windows installer)

## Build

To build the project, simply run:
```shell
$ mvn install
```
To skip the tests, run:

```shell
$ mvn install -DskipTests
```

To only build the `eclair-node` module

```shell
$ mvn install -pl eclair-node -am -DskipTests
```

To generate the windows installer along with the build, run the following command:

```shell
$ mvn install -DskipTests -Pinstaller
```

The generated installer will be located in `eclair-node-gui/target/jfx/installer`

## Docker

A [Dockerfile](Dockerfile) images are built on each commit on [Docker Hub](https://hub.docker.com/r/acinq/eclair) for running a dockerized eclair-node.

You can use the `JAVA_OPTS` environment variable to set arguments to `eclair-node`.

```
$ docker run -ti --rm -e "JAVA_OPTS=-Xmx512m -Declair.api.binding-ip=0.0.0.0 -Declair.node-alias=node-pm -Declair.printToConsole" acinq\eclair
```

If you want to persist the data directory, you can make the volume to your host with the `-v` argument, as the following example:

```
$ docker run -ti --rm -v "/path_on_host:/data" -e "JAVA_OPTS=-Declair.printToConsole" acinq\eclair
```
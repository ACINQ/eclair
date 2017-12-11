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

A [Dockerfile](Dockerfile) is available to run a dockerized eclair-node.

#### Build a local image

```
$ docker build .
```

:warning: if the build fails with `ERROR: unsatisfiable constraints:`, please try again.

#### DockerHub image

An image is built on [Docker Hub](https://hub.docker.com/r/acinq/eclair) for each commit on `Master`.

#### Run the docker image

Please check [these instructions](https://github.com/ACINQ/eclair/wiki/Run#Docker).

# Building Eclair

## Requirements
- [OpenJDK 11](https://jdk.java.net/11/).
- [Maven](https://maven.apache.org/download.cgi) 3.5.4 or newer
- [Docker](https://www.docker.com/) 18.03 or newer (optional) if you want to run all tests

:warning: You can also use [Oracle JDK 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) to build and run eclair, but we recommend you use Open JDK11. 

## Build
To build the project, simply run:
```shell
$ mvn install
```

#### Other build options

To skip all tests, run:
```shell
$ mvn install -DskipTests
```
To only build the `eclair-node` module
```shell
$ mvn install -pl eclair-node -am -DskipTests
```


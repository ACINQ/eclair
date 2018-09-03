# Building Eclair

## Requirements
- [Java Development Kit](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) 1.8u161 or newer
- [Maven](https://maven.apache.org/download.cgi) 3.3.x or newer
- [Inno Setup](http://www.jrsoftware.org/isdl.php) 5.5.9 (optional, if you want to generate the windows installer)
- [Docker](https://www.docker.com/) 18.03 or newer (optional) if you want to run all tests

## Build
To build the project, simply run:
```shell
$ mvn install
```

#### Other build options

If you don't have Docker you can skip tests that depend on it:
```shell
$ mvn install -DexcludedGroups=fr.acinq.eclair.blockchain.electrum.DockerTest
```
To skip all tests, run:
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

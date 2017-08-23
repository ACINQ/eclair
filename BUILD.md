# Building Eclair

## Requirements
- [Java Development Kit](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) 1.8
- [Maven](https://maven.apache.org/download.cgi) 3.3.x
- [Inno Setup](http://www.jrsoftware.org/isdl.php) 5.5.9 (optional, if you want to generate the windows installer)

## Build
To build the project, simply run:
```shell
$ mvn package
```
or
```shell
$ mvn package -DskipTests
```

To generate the windows installer, run the following command:

```shell
mvn package -DskipTests -Pinstaller
```

The generated installer will be located in `eclair-node-gui/target/jfx/installer`

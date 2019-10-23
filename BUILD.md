# Building Eclair

## Requirements

- [OpenJDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot).
- [Maven](https://maven.apache.org/download.cgi) 3.6.0 or newer
- [Docker](https://www.docker.com/) 18.03 or newer (optional) if you want to run all tests

:warning: You can also use [Oracle JDK 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) to build and run eclair, but we recommend you use OpenJDK 11.

## Build

To build the project and run the tests, simply run:

```shell
mvn install
```

### Other build options

To skip all tests, run:

```shell
mvn install -DskipTests
```

To only build the `eclair-node` module, run:

```shell
mvn install -pl eclair-node -am -DskipTests
```

To run the tests, run:

```shell
mvn test
```

To run tests for a specific class, run:

```shell
mvn test -Dsuites=*<TestClassName>
```

## Build the API documentation

### Slate

The API doc is generated via slate and hosted on github pages. To make a change and update the doc follow the steps:

1. `git checkout slate-doc`
2. Install your local dependencies for slate, more info [here](https://github.com/lord/slate#getting-started-with-slate)
3. Edit `source/index.html.md` and save your changes.
4. Commit all the changes to git, before deploying the repo should be clean.
5. Push your commit to remote.
6. Run `./deploy.sh`
7. Wait a few minutes and the doc should be updated at [https://acinq.github.io/eclair](https://acinq.github.io/eclair)

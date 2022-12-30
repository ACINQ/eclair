# Building Eclair

## Requirements

- [OpenJDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot).
- [Maven](https://maven.apache.org/download.cgi) 3.6.0 or newer

## Build

Eclair is packaged as a compressed archive with a launcher script. The archives are built deterministically
so it's possible to reproduce the build and verify its equality byte-by-byte. To build the exact same artifacts
that we release, you must use the build environment (OS, JDK, maven...) that we specify in our release notes.

To build the project and run the tests, simply run:

```shell
mvn package
```

Notes:

- This command will build all modules (core, node, gui).
- If the build fails, you may need to clean previously built artifacts with the `mvn clean` command.
- Archives can be found in the `target` folder for each module.

### Skip tests

Running tests takes time. If you want to skip them, use `-DskipTests`:

```shell
mvn package -DskipTests
```

### Run tests

To only run the tests, run:

```shell
mvn test
```

To run tests for a specific class, run:

```shell
mvn test -Dsuites=*<TestClassName>
```

To run tests using a specific number of threads, run:

```shell
mvn -T <thread_count> test
```

To run tests with a specific version of `bitcoind`, run:

```shell
BITCOIND_DIR=<absolute/path/to/directory> mvn test
```

### Build specific module

To only build the `eclair-node` module, run:

```shell
mvn package -pl eclair-node -am -Dmaven.test.skip=true
```

To install `eclair-core` into your local maven repository and use it in another project, run:

```shell
mvn clean install -pl eclair-core -am -Dmaven.test.skip=true
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

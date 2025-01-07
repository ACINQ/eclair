# Building Eclair

## Requirements

- [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21).
- [Maven](https://maven.apache.org/download.cgi) 3.9.2 or newer

## Build

Eclair is packaged as a compressed archive with a launcher script. The archives are built deterministically
so it's possible to reproduce the build and verify its equality byte-by-byte. To build the exact same artifacts
that we release, you must use the build environment (OS, JDK, maven...) that we specify in our release notes.

To build the project and run the tests, simply run:

```shell
./mvnw package
```

Notes:

- This command will build all modules (core, node, gui).
- If the build fails, you may need to clean previously built artifacts with the `./mvnw clean` command.
- Archives can be found in the `target` folder for each module.

### Skip tests

Running tests takes time. If you want to skip them, use `-DskipTests`:

```shell
./mvnw package -DskipTests
```

### Run tests

To only run the tests, run:

```shell
./mvnw test
```

To run tests for a specific class, run:

```shell
./mvnw test -Dsuites=*<TestClassName>
```

To run tests using a specific number of threads, run:

```shell
./mvnw -T <thread_count> test
```

To run tests with a specific version of `bitcoind`, run:

```shell
BITCOIND_DIR=<absolute/path/to/directory> ./mvnw test
```

### Build specific module

To only build the `eclair-node` module, run:

```shell
./mvnw package -pl eclair-node -am -Dmaven.test.skip=true
```

To install `eclair-core` into your local maven repository and use it in another project, run:

```shell
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
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

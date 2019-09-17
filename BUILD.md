# Building Eclair

## Requirements

- [OpenJDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot).
- [Maven](https://maven.apache.org/download.cgi) 3.6.0 or newer
- [Docker](https://www.docker.com/) 18.03 or newer (optional) if you want to run all tests

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

## Native build (experimental)

This build process leverages graal VM and AOT compilation to create a self contained eclair executable that 
runs *without* any (J)VM.

### Requirements

- [Oracle GraalVM binaries](https://github.com/oracle/graal/releases)
- [Maven](https://maven.apache.org/download.cgi) 3.6.0 or newer
- zlib1g-dev 
- pkg-config

Note that the native build is only available for x86_64 systems and windows support is experimental. 

### Build

After you installed the dependencies it's strongly suggested to set up these environment variables to ease 
the build process:
```shell
$ export GRAALVM_HOME=<graal_installation_directory>
```
```shell
$ export MAVEN_HOME=<maven_installation_directory>
```
```shell
$ export PATH=$PATH:$GRAALVM_HOME/bin:$MAVEN_HOME/bin
```
You also need to install `native-image` from graal's framework:
```shell
$ gu install native-image
```


The native build requires special dependencies to be built before, to start you need libsecp256k1:
```shell
 git clone https://github.com/araspitzu/secp256k1
 cd secp256k1
 git checkout jni_non_static_init
 ./autogen.sh && ./configure --enable-experimental --enable-module_ecdh --enable-jni && make clean && make
 mvn install
```

If you haven't encountered any errors then the package `secp256k1-jni` should be installed in your local maven repo.

Building bitcoin-lib:
```shell
git clone https://github.com/araspitzu/bitcoin-lib
cd bitcoin-lib
git checkout new_jni
mvn install
```

Now let's build eclair-native, assuming you have already cloned this repo:
```shell
git pull && git checkout the_holy_build
JAVA_HOME=GRAALVM_HOME/jre mvn clean install -DskipTests 
```
You can grap a cup of coffee because the build takes around ~12 mins on a fast laptop,
after it finishes you will find the executable in `eclair-node/target/eclair-native`

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

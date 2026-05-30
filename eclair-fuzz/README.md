# Eclair Fuzzing

Eclair uses [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer), a coverage-guided fuzz testing tool.

## Prerequisites

`eclair-fuzz` depends on `eclair-core` which must be installed in your local Maven repository before running fuzz tests:

```shell
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
```

Re-run this command whenever you modify eclair-core source code to ensure fuzz tests exercise your latest changes.

## Fuzzing mode

Actively generates and mutates inputs to maximize code coverage and find bugs. Runs until a crash is found or interrupted. Specify a single fuzz target since each runs indefinitely.

New-coverage inputs are saved to `.cifuzz-corpus/`, crash inputs to `src/test/resources/`.

```shell
JAZZER_FUZZ=1 ./mvnw test -f eclair-fuzz/pom.xml -Dtest=<TestClass>#<fuzzMethod>

# For example:
# JAZZER_FUZZ=1 ./mvnw test -f eclair-fuzz/pom.xml -Dtest=LightningMessageCodecsFuzzTest#fuzzOpenChannel
```

## Regression mode

Replays previously saved crash inputs to verify they no longer fail. This is the default when `JAZZER_FUZZ` is not set.

Set `JAZZER_COVERAGE=1` to also replay inputs from the generated corpus directory (`.cifuzz-corpus/`).

```shell
# Replay crash inputs only (all fuzz targets)
./mvnw test -f eclair-fuzz/pom.xml

# Replay crash inputs + corpus (all fuzz targets)
JAZZER_COVERAGE=1 ./mvnw test -f eclair-fuzz/pom.xml
```

## Coverage report

To generate an HTML coverage report, download and unzip a [JaCoCo](https://github.com/jacoco/jacoco/releases) release, then run:

```shell
./eclair-fuzz/generate-coverage-report.sh /path/to/jacoco/lib
```

This runs the fuzz tests in regression mode (with corpus) under the JaCoCo agent and generates a report at `eclair-fuzz/target/coverage-report/index.html`.

## Updating checksums

`eclair-fuzz` is a standalone module, so the standard checksum recording command (see `BUILD.md`) does not cover it. When updating dependencies in `eclair-fuzz` (e.g. Jazzer), run:

```shell
./mvnw clean install -DskipTests -f eclair-fuzz/pom.xml -Daether.artifactResolver.postProcessor.trustedChecksums.record
```

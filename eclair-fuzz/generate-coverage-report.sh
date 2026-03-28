#!/usr/bin/env bash
set -euo pipefail

# Generate an HTML coverage report for eclair-fuzz tests using JaCoCo.
#
# Usage:
#   ./eclair-fuzz/generate-coverage-report.sh <path/to/jacoco/lib>
#
# Example:
#   ./eclair-fuzz/generate-coverage-report.sh /tmp/jacoco/lib
#
# Prerequisites:
#   - JaCoCo CLI: https://github.com/jacoco/jacoco/releases

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <path/to/jacoco/lib>"
    echo ""
    echo "Download JaCoCo from: https://github.com/jacoco/jacoco/releases"
    echo ""
    echo "Then run:"
    echo "  $0 /path/to/jacoco/lib"
    exit 1
fi

JACOCO_LIB="$1"
JACOCO_AGENT="$JACOCO_LIB/jacocoagent.jar"
JACOCO_CLI="$JACOCO_LIB/jacococli.jar"

if [ ! -f "$JACOCO_AGENT" ]; then
    echo "Error: JaCoCo agent not found at $JACOCO_AGENT"
    exit 1
fi
if [ ! -f "$JACOCO_CLI" ]; then
    echo "Error: JaCoCo CLI not found at $JACOCO_CLI"
    exit 1
fi

EXEC_FILE="$SCRIPT_DIR/target/jacoco.exec"
REPORT_DIR="$SCRIPT_DIR/target/coverage-report"
CLASSFILES="$PROJECT_ROOT/eclair-core/target/classes"
SOURCEFILES="$PROJECT_ROOT/eclair-core/src/main/scala"

if [ ! -d "$CLASSFILES" ]; then
    echo "Error: eclair-core classes not found at $CLASSFILES"
    echo ""
    echo "Build eclair-core first:"
    echo "  ./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true"
    exit 1
fi

echo "==> Running fuzz tests in regression mode with JaCoCo agent..."
cd "$PROJECT_ROOT"
# JAZZER_COVERAGE=1 replays both crash inputs and the generated corpus (.cifuzz-corpus/).
# -DargLine sets JVM options on the forked test JVM launched by Maven Surefire.
# See: https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html#argLine
# The JaCoCo agent is attached via -javaagent to collect execution data:
#   destfile  - where to write the coverage .exec file
#   includes  - restrict instrumentation to eclair packages for faster runs
# See: https://www.jacoco.org/jacoco/trunk/doc/agent.html
JAZZER_COVERAGE=1 ./mvnw test -f eclair-fuzz/pom.xml \
    -DargLine="-javaagent:${JACOCO_AGENT}=destfile=${EXEC_FILE},includes=fr.acinq.eclair.*"

echo "==> Generating HTML coverage report..."
# Use the JaCoCo CLI to convert the .exec data into a human-readable HTML report.
# See: https://www.jacoco.org/jacoco/trunk/doc/cli.html
java -jar "$JACOCO_CLI" report "$EXEC_FILE" \
    --classfiles "$CLASSFILES" \
    --sourcefiles "$SOURCEFILES" \
    --html "$REPORT_DIR" \
    --name "Eclair Fuzz Coverage Report"

echo "==> Report generated at: $REPORT_DIR/index.html"

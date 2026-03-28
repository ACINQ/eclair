#!/usr/bin/env bash
set -euo pipefail

# CI fuzzing script: discovers all @FuzzTest targets and fuzzes each one
# in a separate JVM process since Jazzer only supports one fuzz target per JVM in fuzzing mode.
# See FuzzTestExecutor#prepare: https://github.com/CodeIntelligenceTesting/jazzer/blob/main/src/main/java/com/code_intelligence/jazzer/junit/FuzzTestExecutor.java
#
# Usage:
#   JAZZER_MAX_DURATION=2m ./eclair-fuzz/ci-fuzz.sh
#
# Prerequisites:
#   eclair-core must be installed first:
#     ./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src/test/scala"

# Discover all @FuzzTest methods: extracts "ClassName#methodName" pairs.
# Looks for lines like:  def fuzzSomething(data: FuzzedDataProvider)
# preceded by @FuzzTest annotation.
TARGETS=()
while IFS= read -r src_file; do
    class_name="$(basename "$src_file" .scala)"

    # Extract method names annotated with @FuzzTest
    methods=$(awk '/@FuzzTest/{found=1; next} found && /def [a-zA-Z]/{
        sub(/.*def /, ""); sub(/\(.*/, ""); print; found=0
    }' "$src_file")

    for method in $methods; do
        TARGETS+=("${class_name}#${method}")
    done
done < <(find "$SRC_DIR" -name '*FuzzTest.scala' -type f)

if [ ${#TARGETS[@]} -eq 0 ]; then
    echo "Error: no @FuzzTest targets found in $SRC_DIR"
    exit 1
fi

echo "==> Discovered ${#TARGETS[@]} fuzz target(s):"
for target in "${TARGETS[@]}"; do
    echo "    $target"
done
echo ""

# Fuzz each target in a separate JVM invocation.
# Jazzer can only fuzz one target per JVM process.
FAILED=()
for target in "${TARGETS[@]}"; do
    echo "==> Fuzzing: $target"
    if JAZZER_FUZZ=1 "$PROJECT_ROOT/mvnw" test -f "$SCRIPT_DIR/pom.xml" -Dtest="$target"; then
        echo "==> PASSED: $target"
    else
        echo "==> FAILED: $target"
        FAILED+=("$target")
    fi
    echo ""
done

# Summary
echo "==> Fuzzing complete: ${#TARGETS[@]} target(s), ${#FAILED[@]} failure(s)"
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "==> Failed targets:"
    for target in "${FAILED[@]}"; do
        echo "    $target"
    done
    exit 1
fi

#!/usr/bin/env bash

# Check for JAVA_HOME, fallback to java in PATH
if [[ -n "$JAVA_HOME" ]]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

# Ensure Java is available
if ! command -v "$JAVA_CMD" &> /dev/null; then
    echo "Error: Java not found. Please set JAVA_HOME or ensure 'java' is in your PATH."
    exit 1
fi

CPATH=$(realpath "$(dirname "$0")")

LD_LIBRARY_PATH="$CPATH/natives:./natives" "$JAVA_CMD" --enable-native-access=ALL-UNNAMED -jar "$CPATH/core-@version@.jar" "$@"

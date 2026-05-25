#!/bin/sh
MAVEN_VERSION=3.9.11
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_HOME="$BASE_DIR/.mvn/wrapper/apache-maven-$MAVEN_VERSION"
MAVEN_ZIP="$BASE_DIR/.mvn/wrapper/apache-maven-$MAVEN_VERSION-bin.zip"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.zip" -o "$MAVEN_ZIP"
  unzip -o "$MAVEN_ZIP" -d "$BASE_DIR/.mvn/wrapper" >/dev/null
fi
exec "$MAVEN_HOME/bin/mvn" "$@"

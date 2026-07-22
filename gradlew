#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar is missing. Run scripts/FETCH_GRADLE_WRAPPER.ps1 on Windows or generate the Gradle wrapper once." >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"

#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0

##############################################################################
#
#   Gradle start up script for POSIX generated manually.
#
##############################################################################

APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
APP_BASE_NAME=${0##*/}
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
  JAVACMD=$JAVA_HOME/bin/java
else
  JAVACMD=java
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

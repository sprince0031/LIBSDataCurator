#!/bin/bash

# ----------------------------------
# 1) Identify script base directory
# ----------------------------------
# If the script is located in build/scripts/, then going two levels up gives the project root
BASE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

# Build directories are now relative to root
BUILD_DIR="$BASE_DIR/build"
LIB_DIR="$BUILD_DIR/lib"
SELENIUM_DIR="$LIB_DIR/selenium-java-4.27.0"
CONF_DIR="$BASE_DIR/conf"
LOGS_DIR="$BUILD_DIR/logs"

# ----------------------------------
# 2) Build the CLASSPATH
# ----------------------------------
# Start with conf/ so that property files are found on the classpath
CLASSPATH="$CONF_DIR"

# Append all JARs from lib/ to the classpath
for jarFile in "$LIB_DIR"/*.jar
do
  CLASSPATH="$CLASSPATH:$jarFile"
done

# Append all Selenium JARs from lib/ to the classpath
for jarFile in "$SELENIUM_DIR"/*.jar
do
  CLASSPATH="$CLASSPATH:$jarFile"
done

# ----------------------------------
# 3) Optional: Java system properties
# ----------------------------------
# For example, if your logging.properties is for java.util.logging:
JAVA_OPTS="-Djava.util.logging.config.file=$CONF_DIR/logging.properties"

# You can also pass other system properties:
JAVA_OPTS="$JAVA_OPTS -Duser.dir=$BASE_DIR"
JAVA_OPTS="$JAVA_OPTS -Dapp.logs.dir=$LOGS_DIR"

# ----------------------------------
# 4) Run the application
# ----------------------------------
MAIN_CLASS="com.medals.libsdatagenerator.controller.LIBSDataController"

java $JAVA_OPTS -cp "$CLASSPATH" "$MAIN_CLASS" "$@"
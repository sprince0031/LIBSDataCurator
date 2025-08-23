@echo off
setlocal enabledelayedexpansion
REM Windows batch script to run LIBSDataCurator
REM Equivalent to the Unix run.sh script

REM ----------------------------------
REM 1) Identify script base directory
REM ----------------------------------
set BASE_DIR=%~dp0..
set LIB_DIR=%BASE_DIR%\lib
set SELENIUM_DIR=%LIB_DIR%\selenium-java-4.27.0
set CONF_DIR=%BASE_DIR%\conf
set LOGS_DIR=%BASE_DIR%\logs

REM ----------------------------------
REM 2) Build the CLASSPATH
REM ----------------------------------
REM Start with conf\ so that property files are found on the classpath
set CLASSPATH=%CONF_DIR%

REM Append all JARs from lib\ to the classpath
for %%j in ("%LIB_DIR%\*.jar") do set CLASSPATH=!CLASSPATH!;%%j

REM Append all Selenium JARs from lib\ to the classpath
for %%j in ("%SELENIUM_DIR%\*.jar") do set CLASSPATH=!CLASSPATH!;%%j

REM ----------------------------------
REM 3) Java system properties
REM ----------------------------------
REM Configure logging properties file
set JAVA_OPTS=-Djava.util.logging.config.file=%CONF_DIR%\logging.properties

REM Set other system properties
set JAVA_OPTS=%JAVA_OPTS% -Duser.dir=%BASE_DIR%
set JAVA_OPTS=%JAVA_OPTS% -Dapp.logs.dir=%LOGS_DIR%

REM ----------------------------------
REM 4) Run the application
REM ----------------------------------
set MAIN_CLASS=com.medals.libsdatagenerator.controller.LIBSDataController

java %JAVA_OPTS% -cp "%CLASSPATH%" "%MAIN_CLASS%" %*
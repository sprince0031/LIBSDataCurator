@echo off
REM ============================================================================
REM LIBS Instrument Profile Calibration Script (Windows)
REM ============================================================================
REM Generates instrument profile from real LIBS measurement data.
REM This script is part of the LIBSDataCurator calibration mode.
REM
REM Usage: calibrate.bat -i ^<sample_csv^> -c ^<composition^> [-o ^<output^>] [-n ^<name^>]
REM
REM Arguments:
REM   -i, --input       Path to sample LIBS measurement CSV file (required)
REM   -c, --composition Reference material composition, e.g., "Fe-98.0,C-0.5" (required)
REM   -o, --output      Output path for profile JSON (default: instrument_profile.json)
REM   -n, --name        Instrument name/identifier (default: Unknown)
REM   -h, --help        Show this help message
REM
REM Example:
REM   calibrate.bat -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5"
REM ============================================================================

setlocal enabledelayedexpansion

REM Get the directory where this script resides
set SCRIPT_DIR=%~dp0
set BASE_DIR=%SCRIPT_DIR%..

REM Check for custom JRE (self-contained package) or use system Java
if exist "%BASE_DIR%\jre-custom\bin\java.exe" (
    set JAVA_EXEC=%BASE_DIR%\jre-custom\bin\java.exe
    echo Using bundled JRE: %JAVA_EXEC%
) else (
    set JAVA_EXEC=java
    echo Using system Java
)

REM Check if Java is available
"%JAVA_EXEC%" -version >nul 2>&1
if errorlevel 1 (
    echo Error: Java not found. Please install Java 21 or higher.
    exit /b 1
)

REM Find the JAR file
set JAR_FILE=%BASE_DIR%\lib\LIBSDataCurator.jar
if not exist "%JAR_FILE%" (
    set JAR_FILE=%BASE_DIR%\target\LIBSDataCurator.jar
    if not exist "!JAR_FILE!" (
        set JAR_FILE=%BASE_DIR%\build\target\LIBSDataCurator.jar
        if not exist "!JAR_FILE!" (
            echo Error: LIBSDataCurator.jar not found.
            echo Please run 'mvn package' first to build the application.
            exit /b 1
        )
    )
)

echo Using JAR: %JAR_FILE%
echo.

REM Run the instrument profile controller
"%JAVA_EXEC%" -cp "%JAR_FILE%" com.medals.libsdatagenerator.controller.InstrumentProfileController %*

endlocal

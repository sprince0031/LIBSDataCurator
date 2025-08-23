@echo off
REM Local build script for Windows that mirrors the CI/CD workflow
REM This script creates self-contained packages with bundled JRE

setlocal enabledelayedexpansion

echo === LIBSDataCurator Local Build Script (Windows) ===
echo.

REM Clean previous builds
echo Cleaning previous builds...
call mvn clean
if exist release-package rmdir /s /q release-package
if exist jre-custom rmdir /s /q jre-custom
del /q LIBSDataCurator-*.zip 2>nul

echo.

REM Run tests
echo Running tests...
call mvn test
if errorlevel 1 (
    echo ERROR: Tests failed
    exit /b 1
)

echo.

REM Build application
echo Building application...
call mvn package -DskipTests
if errorlevel 1 (
    echo ERROR: Build failed
    exit /b 1
)

echo.

REM Check if jlink is available
where jlink >nul 2>&1
if errorlevel 1 (
    echo ERROR: jlink not found. Please ensure you have JDK 21+ installed.
    exit /b 1
)

echo Creating custom JRE with jlink...
jlink --add-modules java.se,java.security.jgss,java.security.sasl,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.security.auth,jdk.security.jgss --strip-debug --no-man-pages --no-header-files --compress=2 --output ./jre-custom
if errorlevel 1 (
    echo ERROR: jlink failed
    exit /b 1
)

echo Updating cacerts with system certificate store...
REM Update cacerts - try common Windows JDK locations
if exist "C:\Program Files\Eclipse Adoptium\jdk-21*\lib\security\cacerts" (
    for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
        if exist "%%i\lib\security\cacerts" (
            copy "%%i\lib\security\cacerts" .\jre-custom\lib\security\cacerts
            echo Updated cacerts from system store
            goto :cacerts_done
        )
    )
)
if exist "%JAVA_HOME%\lib\security\cacerts" (
    copy "%JAVA_HOME%\lib\security\cacerts" .\jre-custom\lib\security\cacerts
    echo Updated cacerts from JAVA_HOME
) else (
    echo Warning: System cacerts not found, using default
)
:cacerts_done

echo.

REM Get version from pom.xml with error handling
echo Getting project version...

REM Use 'call' to execute the Maven batch script and ensure control returns.
call mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>nul > temp_version.txt

REM Check the errorlevel immediately after the command.
REM A non-zero errorlevel indicates the previous command failed.
if %errorlevel% neq 0 (
    echo ERROR: Maven command failed with errorlevel %errorlevel%.
    echo Please run the 'mvn' command manually to diagnose the issue.
    if exist temp_version.txt del temp_version.txt
    exit /b 1
)

REM Check if the output file was created, as a fallback.
if not exist temp_version.txt (
    echo ERROR: Maven command ran but failed to produce an output file. 
    if exist temp_version.txt del temp_version.txt
    exit /b 1
)

REM Read the version from the temporary file
set /p VERSION=<temp_version.txt

REM Clean up the temporary file
del temp_version.txt

REM Check if the VERSION variable was successfully set
if not defined VERSION (
    echo ERROR: Could not read version from the output file. It may be empty.
    exit /b 1
)

echo Preparing release package (version: %VERSION%, platform: windows)...

REM Create directory structure
mkdir release-package\lib
mkdir release-package\bin
mkdir release-package\conf
mkdir release-package\data
mkdir release-package\logs
mkdir release-package\docs

REM Copy JAR to release package
copy target\LIBSDataCurator.jar release-package\lib\

REM Copy JAR to Build\lib for backwards compatibility
if not exist Build\lib mkdir Build\lib
copy target\LIBSDataCurator.jar Build\lib\

REM Copy custom JRE
xcopy /s /e /i jre-custom release-package\jre-custom

REM Copy configuration files if they exist
if exist "Build\conf" (
    xcopy /s /e Build\conf\* release-package\conf\ 2>nul
)

REM Copy docs if they exist
if exist "docs" (
    xcopy /s /e docs\* release-package\docs\ 2>nul
)

REM Create run script
(
echo @echo off
echo set SCRIPT_DIR=%%~dp0
echo set MAIN_DIR=%%SCRIPT_DIR%%..
echo.
echo REM Use bundled JRE
echo set JAVA_HOME=%%MAIN_DIR%%\jre-custom
echo.
echo REM Change to package directory so application can find conf files
echo cd /d "%%MAIN_DIR%%"
echo.
echo REM Run the application
echo "%%JAVA_HOME%%\bin\java.exe" -jar "%%MAIN_DIR%%\lib\LIBSDataCurator.jar" %%*
) > release-package\bin\run.bat

REM Create README
(
echo LIBSDataCurator %VERSION%
echo.
echo This is a self-contained package that includes:
echo - The application JAR file
echo - A custom Java Runtime Environment ^(JRE^)
echo - Configuration files
echo - Documentation
echo.
echo To run the application:
echo.
echo Windows:
echo bin\run.bat [arguments]
echo.
echo The application includes its own JRE, so you don't need Java installed on your system.
echo.
echo For help with command line arguments:
echo bin\run.bat --help
) > release-package\README.txt

echo.

REM Create archive
set ARCHIVE_NAME=LIBSDataCurator-%VERSION%-windows.zip
echo Creating archive: %ARCHIVE_NAME%

REM Use PowerShell to create zip
powershell -command "Compress-Archive -Path 'release-package\*' -DestinationPath '%ARCHIVE_NAME%' -Force"
if errorlevel 1 (
    echo ERROR: Failed to create archive
    exit /b 1
)

echo.
echo === Build Complete ===
echo Archive created: %ARCHIVE_NAME%

REM Get file size
for %%A in ("%ARCHIVE_NAME%") do set SIZE=%%~zA
set /a SIZE_MB=!SIZE!/1024/1024
echo Size: !SIZE_MB! MB

echo.
echo To test the package:
echo 1. Extract the zip file
echo 2. Run: bin\run.bat --help
echo.

pause
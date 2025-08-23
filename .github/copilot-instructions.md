# LIBS Data Curator

LIBS Data Curator is a Java 21 Maven application for automated collection of Laser-Induced Breakdown Spectroscopy (LIBS) data from the NIST LIBS database. The tool uses Selenium WebDriver for web automation and supports compositional variations with statistical sampling.

Always reference these instructions first and fallback to search or bash commands only when you encounter unexpected information that does not match the info here.

## Working Effectively

### Prerequisites and Environment Setup
- Install Java 21 (required - application will not build with older versions):
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH=$JAVA_HOME/bin:$PATH
  ```
- Verify Java 21 is active: `java --version` (should show version 21.x.x)
- Maven is typically pre-installed - verify with: `mvn --version`

### Bootstrap, Build, and Test Commands
Always run these commands in the repository root directory:

1. **Clean and build the project:**
   ```bash
   mvn clean package
   ```
   - **NEVER CANCEL**: Build takes 35-40 seconds. Set timeout to 90+ minutes for safety.
   - Creates fat JAR with all dependencies: `target/LIBSDataCurator.jar`
   - Copies JAR to `Build/lib/LIBSDataCurator.jar`
   - Creates distribution archives: `target/LIBSDataCurator-0.8.tar.gz` and `.zip`

2. **Run tests:**
   ```bash
   mvn test
   ```
   - **NEVER CANCEL**: Tests take 3-5 seconds. Set timeout to 30+ minutes for safety.
   - Runs 19 unit tests (3 may be skipped due to environment constraints)
   - All tests should pass - if any fail, investigate before proceeding

3. **Build only (skip tests for faster iteration):**
   ```bash
   mvn compile package -DskipTests
   ```
   - Takes about 25-30 seconds when dependencies are cached

### Self-Contained Package Building
The project supports creating self-contained packages with bundled Java Runtime Environment (JRE):

4. **Local packaging for Linux/macOS:**
   ```bash
   ./build-local.sh
   ```
   - **NEVER CANCEL**: Takes 2-5 minutes. Set timeout to 10+ minutes for safety.
   - Creates self-contained `LIBSDataCurator-{version}-linux.tar.gz`
   - Includes custom JRE with SSL/TLS support
   - Uses jlink for optimized runtime

5. **Local packaging for Windows:**
   ```batch
   build-local.bat
   ```
   - **NEVER CANCEL**: Takes 2-5 minutes. Set timeout to 10+ minutes for safety.
   - Creates self-contained `LIBSDataCurator-{version}-windows.zip`
   - Includes custom JRE with SSL/TLS support

**Key Features of Self-Contained Packages:**
- No Java installation required on target systems
- SSL/TLS support with updated certificate store for NIST LIBS connectivity
- Complete Selenium WebDriver dependencies included
- Cross-platform support (Windows and Linux/macOS)
- Optimized custom JRE using jlink with security modules

### Running the Application

**CRITICAL**: The application requires network access to the NIST LIBS database (physics.nist.gov) for full functionality. In restricted environments, it will show warnings but basic CLI validation still works.

#### Method 1: Using the Fat JAR (Recommended)
```bash
# From repository root
java -jar target/LIBSDataCurator.jar [options]

# Example with composition
java -jar target/LIBSDataCurator.jar -c "Fe-80,C-20" --min-wavelength 200 --max-wavelength 300
```

#### Method 3: Using Self-Contained Packages (Recommended for Distribution)
```bash
# Linux/macOS - extract and run
tar -xzf LIBSDataCurator-{version}-linux.tar.gz
cd LIBSDataCurator-{version}/
./bin/run.sh [options]

# Windows - extract and run
# Extract LIBSDataCurator-{version}-windows.zip
# Navigate to extracted folder
bin\run.bat [options]

# Examples
./bin/run.sh -c "Fe-80,C-20" --min-wavelength 200 --max-wavelength 300
```
#### Method 2: Using the Run Script
```bash
# Create required directories first
mkdir -p Build/logs Build/data

# Run the script
Build/bin/run.sh [options]

# Example
Build/bin/run.sh -c "Fe-80,C-20"
```

#### Common CLI Options
- `-c, --composition`: Material composition (e.g., "Fe-80,C-20") or MatWeb GUID
- `--min-wavelength`: Minimum wavelength in nm (default: 200)
- `--max-wavelength`: Maximum wavelength in nm (default: 800)
- `-o, --output`: Output directory path
- `-v, --compvar`: Enable compositional variations
- `-s, --series`: Process steel series from materials catalogue
- `--help`: Show full help (run with no arguments)

### Validation

#### Always Run These Validation Steps After Making Changes:
1. **Build validation:**
   ```bash
   mvn clean package
   ```
   - Must complete without errors
   - Verify JAR files are created in target/ and Build/lib/

2. **Test validation:**
   ```bash
   mvn test
   ```
   - All tests must pass (some may be skipped due to environment)
   - Check for any new failures related to your changes

3. **CLI validation:**
   ```bash
   java -jar target/LIBSDataCurator.jar
   ```
   - Should display usage help without errors (may show network warnings)
   - Verify command-line argument parsing works correctly

5. **Self-contained package validation:**
   ```bash
   # Test local build script (Linux/macOS)
   ./build-local.sh
   
   # Test local build script (Windows) 
   build-local.bat
   
   # Test generated package
   tar -xzf LIBSDataCurator-*-linux.tar.gz  # Linux/macOS
   cd LIBSDataCurator-*/
   ./bin/run.sh --help  # Should show usage without needing Java installed
   ```
   - Verify self-contained packages work without requiring Java installation
   - Check SSL/TLS connectivity fixes are included
4. **Argument validation test:**
   ```bash
   java -jar target/LIBSDataCurator.jar -c "Fe-50,C-50"
   ```
   - Should start processing (may fail due to network restrictions)
   - Verify composition parsing works correctly

#### Manual Validation Scenarios:
**CRITICAL**: Always test these scenarios after making changes to ensure the application works correctly:

1. **CLI Argument Processing Test:**
   - Run `java -jar target/LIBSDataCurator.jar -c "Fe-80,C-20"` 
   - Verify composition parsing logs appear
   - Check that argument validation works correctly

2. **Help System Test:**
   - Run `java -jar target/LIBSDataCurator.jar` (no arguments)
   - Verify complete help text is displayed
   - Check all command-line options are documented

3. **Error Handling Test:**
   - Run `java -jar target/LIBSDataCurator.jar -c "InvalidElement-50,Fe-50"`
   - Verify appropriate error messages for invalid elements

### Known Issues and Workarounds

1. **Network Connectivity Required:**
   - Application needs access to physics.nist.gov for full functionality
   - In restricted environments, expect "UnknownHostException" warnings
   - Basic CLI validation and composition parsing still work offline

2. **Logging Configuration:**
   - Default logging config has hardcoded paths that may not exist
   - Create required directories: `mkdir -p Build/logs Build/data`
   - Logging errors don't prevent application functionality

4. **SSL/TLS Connectivity:**
   - Custom JRE includes comprehensive security modules for HTTPS connections
   - Certificate store updated with system certificates for NIST LIBS database access
   - SSL handshake exceptions resolved in self-contained packages

5. **Self-Contained Package Artifacts:**
   - Build artifacts include `jre-custom/`, `release-package/` directories
   - Platform-specific packages: `LIBSDataCurator-*-linux.tar.gz`, `LIBSDataCurator-*-windows.zip`
   - Added to .gitignore to prevent accidental commits
3. **Browser Automation:**
   - Selenium WebDriver requires compatible browser environment
   - In headless environments, full web automation may not work
   - Core composition processing logic still functions

### CI/CD Integration

The project uses GitHub Actions for automated building, testing, and releases:

**Test Workflow** (`.github/workflows/build.yml`):
- **Triggered on**: PRs to main/dev branches and pushes to main/dev
- **Actions**: Runs tests and builds application (no packaging)
- **Always ensure**: Your changes don't break the CI build

**Release Workflow** (`.github/workflows/release.yml`):
- **Triggered on**: Git tags matching pattern `v*` (e.g., `v1.0.0`, `v0.8.1`)
- **Actions**: Creates multiplatform self-contained packages with bundled JRE
- **Outputs**: Linux and Windows packages as GitHub Release assets

**Creating Releases:**
```bash
git tag v1.0.0
git push origin v1.0.0
```
- Automatically triggers multiplatform package creation
- Creates GitHub Release with download links
- Includes SSL/TLS fixes and complete Selenium integration

**Timeout warnings**: CI builds may take up to 10 minutes - **NEVER CANCEL**

### Performance Expectations

- **Initial Maven dependency download**: Up to 15 minutes on first run
- **Subsequent builds**: 30-40 seconds
- **Test execution**: 3-5 seconds
- **Self-contained package creation**: 2-5 minutes
- **jlink custom JRE creation**: 30-60 seconds
- **Full application startup**: 10-15 seconds (plus network connectivity checks)

### Project Structure

Key directories and files to know:
```
LIBSDataCurator/
├── src/main/java/com/medals/libsdatagenerator/
│   ├── controller/           # Main application control (LIBSDataController)
│   ├── service/             # Core business logic
│   ├── util/                # Helper utilities and CLI parsing
│   └── model/               # Data models (Element, etc.)
├── src/test/java/           # JUnit 5 tests
├── Build/
│   ├── bin/run.sh          # Runtime script
│   ├── conf/               # Configuration files
│   ├── lib/                # JAR files (created by build)
│   ├── logs/               # Application logs (create manually)
│   └── data/               # Output data (create manually)
├── build-local.sh          # Local packaging script (Linux/macOS)
├── build-local.bat         # Local packaging script (Windows)
├── BUILD.md                # Comprehensive build documentation
├── docs/                   # Documentation
├── pom.xml                 # Maven configuration
└── .github/workflows/      # CI/CD configuration
    ├── build.yml           # Test workflow
    └── release.yml         # Release packaging workflow
```

### Common Troubleshooting

- **"java: command not found"**: Ensure Java 21 is installed and JAVA_HOME is set
- **Build failures**: Check Java version is 21.x.x with `java --version`
- **Test failures**: Run `mvn clean test` to see detailed error output
- **JAR not found**: Ensure `mvn package` completed successfully
- **Permission errors**: Check file permissions, especially for `Build/bin/run.sh`
- **jlink not found**: Ensure JDK 21+ is installed (not just JRE) for self-contained packaging
- **SSL handshake errors**: Use self-contained packages which include updated certificate stores
- **Large package size**: Self-contained packages are ~50MB due to bundled JRE (normal)

Always prioritize fixing build and test failures before implementing new features.

## Common Tasks

The following are outputs from frequently run commands. Reference them instead of viewing, searching, or running bash commands to save time.

### Repository Root Structure
```
ls -la (repo root)
total 60
drwxr-xr-x  8 runner docker 4096 Aug 21 16:48 .
drwxr-xr-x  3 runner docker 4096 Aug 21 16:21 ..
drwxr-xr-x  7 runner docker 4096 Aug 21 16:47 .git
drwxr-xr-x  3 runner docker 4096 Aug 21 16:48 .github
-rw-r--r--  1 runner docker  408 Aug 21 16:21 .gitignore
drwxr-xr-x  7 runner docker 4096 Aug 21 16:39 Build
-rw-r--r--  1 runner docker 7340 Aug 21 18:04 BUILD.md (Comprehensive build guide)
-rwxr-xr-x  1 runner docker 4720 Aug 21 18:04 build-local.sh (Linux/macOS packaging)
-rw-r--r--  1 runner docker 5690 Aug 21 18:04 build-local.bat (Windows packaging)
drwxr-xr-x  3 runner docker 4096 Aug 21 16:21 docs
-rw-r--r--  1 runner docker 8268 Aug 21 16:21 pom.xml
drwxr-xr-x  5 runner docker 4096 Aug 21 16:21 src
drwxr-xr-x 11 runner docker 4096 Aug 21 16:49 target
```

### Key Source Files
Main application classes:
- `src/main/java/com/medals/libsdatagenerator/controller/LIBSDataController.java` - Main entry point
- `src/main/java/com/medals/libsdatagenerator/service/LIBSDataService.java` - Core data processing
- `src/main/java/com/medals/libsdatagenerator/service/CompositionalVariations.java` - Composition calculations
- `src/main/java/com/medals/libsdatagenerator/util/CommonUtils.java` - CLI parsing and utilities
- `src/main/java/com/medals/libsdatagenerator/model/Element.java` - Data model

Test files:
- `src/test/java/com/medals/libsdatagenerator/controller/TestCLI.java` - CLI argument tests
- `src/test/java/com/medals/libsdatagenerator/service/CompositionalVariationsTest.java` - Composition logic tests
- `src/test/java/com/medals/libsdatagenerator/util/ElementPropertiesTest.java` - Element handling tests

### Build Artifacts After Successful Build
**Standard Maven Build (`mvn package`):**
```
ls -la target/ (first 5 entries)
total 109020
drwxr-xr-x 11 runner docker     4096 Aug 21 16:49 .
drwxr-xr-x  8 runner docker     4096 Aug 21 16:48 ..
-rw-r--r--  1 runner docker 28678871 Aug 21 16:50 LIBSDataCurator-0.8.jar (Fat JAR)
-rw-r--r--  1 runner docker 27074596 Aug 21 16:50 LIBSDataCurator-0.8.tar.gz (Distribution)
-rw-r--r--  1 runner docker 27075731 Aug 21 16:50 LIBSDataCurator-0.8.zip (Distribution)
```

**Self-Contained Package Build (`./build-local.sh` or `build-local.bat`):**
```
ls -la (additional artifacts)
drwxr-xr-x  6 runner docker     4096 Aug 21 18:05 jre-custom/ (Custom JRE)
drwxr-xr-x  8 runner docker     4096 Aug 21 18:05 release-package/ (Packaging staging)
-rw-r--r--  1 runner docker 52428800 Aug 21 18:05 LIBSDataCurator-0.8-linux.tar.gz (Self-contained)
-rw-r--r--  1 runner docker 52194304 Aug 21 18:05 LIBSDataCurator-0.8-windows.zip (Self-contained)
```

**Self-Contained Package Structure:**
```
LIBSDataCurator-{version}/
├── bin/
│   ├── run.sh (Linux/macOS executable)
│   └── run.bat (Windows executable)
├── lib/
│   └── LIBSDataCurator.jar
├── jre-custom/ (Bundled Java Runtime with SSL support)
├── conf/ (Configuration files)
├── data/ (Output directory)
├── logs/ (Log directory)
├── docs/ (Documentation)
└── README.txt (Usage instructions)
```

### CI/CD Workflow Files
**Test Workflow (`.github/workflows/build.yml`):**
```yaml
name: CI - Build and Test
on:
  push:
    branches: [ main, dev ]
  pull_request:
    branches: [ main, dev ]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4 (JDK 21, temurin, maven cache)
      - run: mvn -B test --file pom.xml
      - run: mvn -B package -DskipTests --file pom.xml
```

**Release Workflow (`.github/workflows/release.yml`):**
- Triggered by git tags (`v*` pattern)
- Matrix build: Linux and Windows
- Creates custom JRE with jlink + security modules
- Updates SSL certificate store
- Packages self-contained distributions
- Uploads to GitHub Releases
### Configuration Files
```
ls -la Build/conf/
total 16
drwxr-xr-x 2 runner docker 4096 Aug 21 16:21 .
drwxr-xr-x 7 runner docker 4096 Aug 21 16:39 ..
-rw-r--r-- 1 runner docker  434 Aug 21 16:21 logging.properties (Logging config - has hardcoded paths)
-rw-r--r-- 1 runner docker 1831 Aug 21 16:21 materials_catalogue.properties (Steel series definitions)
```

### Local Build Script Usage
**Linux/macOS (./build-local.sh):**
```bash
=== LIBSDataCurator Local Build Script ===
Cleaning previous builds...
Running tests...
Building application...
Creating custom JRE with jlink...
Updating cacerts with system certificate store...
Preparing release package (version: 0.8, platform: linux)...
Creating archive: LIBSDataCurator-0.8-linux.tar.gz
=== Build Complete ===
Archive created: LIBSDataCurator-0.8-linux.tar.gz
Size: 50M
```

**Windows (build-local.bat):**
```batch
=== LIBSDataCurator Local Build Script (Windows) ===
Cleaning previous builds...
Running tests...
Building application...
Creating custom JRE with jlink...
Updating cacerts with system certificate store...
Preparing release package (version: 0.8, platform: windows)...
Creating archive: LIBSDataCurator-0.8-windows.zip
=== Build Complete ===
Archive created: LIBSDataCurator-0.8-windows.zip
Size: 50 MB
```
### Help Output (Application Usage)
```
java -jar target/LIBSDataCurator.jar (no arguments)
usage: java LIBSDataGenerator
 -c,--composition <arg>        Composition of the material in the format
                               "Element1-Percentage1,Element2-Percentage2,
                               ...". OR input a GUID from the URL of a 
                               material's datasheet from matweb.com
 -ff,--force-fetch             Force re-downloading of spectrum data
 -max,--max-wavelength <arg>   Maximum wavelength (Nm)
 -min,--min-wavelength <arg>   Minimum wavelength (Nm)
 -o,--output <arg>             Path to save CSV file
 -s,--series <arg>             Specify a steel series key from properties file
 -v,--compvar                  Perform compositional variations
 -vb,--vary-by <arg>           Step size for compositional variations
 -vm,--variation-mode <arg>    Variation mode (0=uniform, 1=gaussian, 2=dirichlet)
```

### Expected Application Startup Messages
**Normal startup (with network warnings in restricted environments):**
```
Aug 21, 2025 4:49:22 PM com.medals.libsdatagenerator.controller.LIBSDataController main
INFO: Starting LIBS Data Curator...
Aug 21, 2025 4:49:22 PM com.medals.libsdatagenerator.controller.LIBSDataController main
INFO: Initialising LIBS Data extraction...
Aug 21, 2025 4:49:22 PM com.medals.libsdatagenerator.util.CommonUtils isWebsiteReachable
SEVERE: Exception occurred while trying to connect to https://physics.nist.gov/PhysRefData/ASD/LIBS/libs-form.html
java.net.UnknownHostException: physics.nist.gov
Aug 21, 2025 4:49:22 PM com.medals.libsdatagenerator.controller.LIBSDataController main
WARNING: NIST LIBS reachable is not available
```

This is normal behavior in restricted network environments and doesn't prevent basic functionality.

**Self-contained package startup (with SSL support):**
When using self-contained packages, SSL/TLS connections to NIST LIBS database should work correctly due to updated certificate stores and included security modules. Network connectivity warnings may still appear in firewalled environments but SSL handshake exceptions should be resolved.
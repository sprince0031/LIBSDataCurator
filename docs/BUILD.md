# Build and Packaging Guide

This document describes the automated build and packaging system for LIBSDataCurator.

## Overview

The project provides both local and CI/CD workflows for building and packaging the application. The packaging creates self-contained distributions that include a custom Java Runtime Environment (JRE), eliminating the need for users to have Java installed.

## Local Building

### Prerequisites

- JDK 21 or higher (includes jlink tool)
- Maven 3.6+

### Quick Start

#### Linux/macOS
```bash
./Build/scripts/build-local.sh
```

#### Windows
```batch
Build\scripts\build-local.bat
```

These scripts will:
1. Clean previous builds
2. Run tests
3. Build the application
4. Create a custom JRE using jlink
5. Package everything into a self-contained archive

### Output

The build creates:
- **Linux/macOS**: `LIBSDataCurator-{version}-linux.tar.gz`
- **Windows**: `LIBSDataCurator-{version}-windows.zip`

Each package contains:
- `lib/` - Application JAR file
- `jre-custom/` - Bundled Java Runtime Environment
- `bin/` - Startup scripts (`run.sh` or `run.bat`)
- `conf/` - Configuration files
- `data/` - Data directory
- `logs/` - Log directory  
- `docs/` - Documentation
- `README.txt` - Usage instructions

### Manual Steps

If you prefer manual control:

```bash
# Run tests
mvn test

# Build application
mvn package -DskipTests

# Create custom JRE (includes SSL/TLS and security modules)
jlink --add-modules java.se,java.security.jgss,java.security.sasl,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.security.auth,jdk.security.jgss \
      --strip-debug --no-man-pages --no-header-files --compress=2 \
      --output ./jre-custom

# Update certificate store for SSL connections
if [ -f "/etc/ssl/certs/adoptium/cacerts" ]; then
    cp /etc/ssl/certs/adoptium/cacerts ./jre-custom/lib/security/cacerts
fi

# Package manually (follow Build/scripts/build-local.sh for structure)
```

## CI/CD Workflows

### Test Workflow (`.github/workflows/build.yml`)

**Triggered by**:
- Pushes to `main` or `dev` branches
- Pull requests targeting `main` or `dev` branches

**Actions**:
- Sets up JDK 21
- Runs all tests
- Builds the application (no packaging)

### Release Workflow (`.github/workflows/release.yml`)

**Triggered by**:
- Creating tags matching pattern `v*` (e.g., `v1.0.0`, `v0.8.1`)

**Actions**:
- Creates self-contained packages for both Linux and Windows
- Uses jlink to bundle custom JRE
- Uploads packages as GitHub Release assets

### Creating a Release

1. **Tag a commit on main branch**:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. **GitHub Actions will automatically**:
   - Build multiplatform packages
   - Create a GitHub Release
   - Upload the packages as release assets

## Package Usage

### Extracting and Running

#### Linux/macOS
```bash
tar -xzf LIBSDataCurator-{version}-linux.tar.gz
cd LIBSDataCurator-{version}/
./bin/run.sh [arguments]
```

#### Windows
```batch
# Extract the zip file
# Navigate to extracted folder
bin\run.bat [arguments]
```

### Example Usage

```bash
# Show usage information
./bin/run.sh -c "Fe-50,Cr-30,Ni-20" -o output.csv

# Generate variations
./bin/run.sh -c "Fe-70,C-1.5,Mn-1,Cr-#" -v -n 100 -o steel_data.csv
```

## Configuration

### JRE Modules

The custom JRE includes the complete Java SE platform (`java.se`) plus additional security modules for SSL/TLS connections:
- `java.se` - Complete Java SE platform (includes base, logging, desktop, naming, xml, net.http, etc.)
- `java.security.jgss` - Generic Security Services (GSS-API)
- `java.security.sasl` - Simple Authentication and Security Layer (SASL)
- `java.xml.crypto` - XML Digital Signature APIs
- `jdk.crypto.cryptoki` - Cryptographic Token Interface (PKCS#11)
- `jdk.crypto.ec` - Elliptic Curve Cryptography provider
- `jdk.security.auth` - Authentication and authorization
- `jdk.security.jgss` - GSS-API Kerberos mechanism

**SSL/TLS Support**: The build process updates the certificate store (`cacerts`) with system certificates to ensure proper SSL/TLS connections to external services like NIST LIBS database.

**Selenium Integration**: All Selenium WebDriver dependencies are included in the JAR via Maven's assembly plugin, ensuring web scraping functionality works correctly.

To modify modules, edit the `jlink` command in:
- `Build/scripts/build-local.sh` (Linux/macOS)
- `Build/scripts/build-local.bat` (Windows)
- `.github/workflows/release.yml` (CI/CD)

### Build Configuration

Key Maven plugins:
- `maven-assembly-plugin` - Creates fat JAR with dependencies
- `maven-surefire-plugin` - Runs tests
- `maven-compiler-plugin` - Java 21 compilation

## Troubleshooting

### Common Issues

1. **jlink not found**
   - Ensure you have JDK 21+ (not just JRE)
   - Verify `JAVA_HOME` points to JDK

2. **Build fails on Java version**
   - Project requires Java 21+
   - Check `java -version` and `mvn -version`

3. **Tests fail**
   - Some tests require internet connectivity
   - Run with `-DskipTests` to skip tests during development

4. **Large package size**
   - Custom JRE is ~50MB
   - This is normal for self-contained packages
   - Alternative: Use regular JAR and require Java installation

### Getting Help

- Check the main application usage: `./bin/run.sh` (will show usage)
- Review logs in the `logs/` directory
- Check Maven output for build issues

## Development Notes

### Local vs CI/CD Differences

- **Local builds**: Use system Java, create single platform package
- **CI/CD builds**: Use GitHub Actions runners, create multiplatform packages
- **Both**: Use same jlink configuration and package structure

### Maintenance

When updating:
- **Java version**: Update in `pom.xml` and workflow files
- **Dependencies**: Update in `pom.xml`
- **JRE modules**: Update jlink commands in all scripts
- **Packaging**: Modify build scripts and assembly configuration
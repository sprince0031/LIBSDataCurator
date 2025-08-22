#!/bin/bash

# Local build script that mirrors the CI/CD workflow
# This script creates self-contained packages with bundled JRE

set -e  # Exit on any error

echo "=== LIBSDataCurator Local Build Script ==="
echo

# Clean previous builds
echo "Cleaning previous builds..."
mvn clean
rm -rf release-package
rm -rf jre-custom
rm -f LIBSDataCurator-*.tar.gz
rm -f LIBSDataCurator-*.zip

echo

# Run tests
echo "Running tests..."
mvn test

echo

# Build application
echo "Building application..."
mvn package -DskipTests

echo

# Check if jlink is available
if ! command -v jlink &> /dev/null; then
    echo "ERROR: jlink not found. Please ensure you have JDK 21+ installed."
    exit 1
fi

echo "Creating custom JRE with jlink..."
jlink --add-modules java.se,java.security.jgss,java.security.sasl,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.security.auth,jdk.security.jgss \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=2 \
      --output ./jre-custom

echo "Updating cacerts with system certificate store..."
# Update cacerts with system certificate store to fix SSL issues
if [ -f "/etc/ssl/certs/adoptium/cacerts" ]; then
    cp /etc/ssl/certs/adoptium/cacerts ./jre-custom/lib/security/cacerts
    echo "Updated cacerts from system store"
elif [ -f "/etc/ssl/certs/java/cacerts" ]; then
    cp /etc/ssl/certs/java/cacerts ./jre-custom/lib/security/cacerts
    echo "Updated cacerts from system Java store"
else
    echo "Warning: System cacerts not found, using default"
fi

echo

# Get version from pom.xml
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
PLATFORM=$(uname -s | tr '[:upper:]' '[:lower:]')

echo "Preparing release package (version: $VERSION, platform: $PLATFORM)..."

# Create directory structure
mkdir -p release-package/{lib,bin,conf,data,logs,docs}

# Copy JAR
cp target/LIBSDataCurator.jar release-package/lib/

# Copy custom JRE
cp -r jre-custom release-package/

# Copy configuration files if they exist
if [ -d "Build/conf" ]; then
    cp -r Build/conf/* release-package/conf/ 2>/dev/null || true
fi

# Copy docs if they exist
if [ -d "docs" ]; then
    cp -r docs/* release-package/docs/ 2>/dev/null || true
fi

# Create run script
cat > release-package/bin/run.sh << 'EOF'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_DIR="$(dirname "$SCRIPT_DIR")"

# Use bundled JRE
JAVA_HOME="$MAIN_DIR/jre-custom"
export JAVA_HOME

# Change to package directory so application can find conf files
cd "$MAIN_DIR"

# Run the application
"$JAVA_HOME/bin/java" -jar "$MAIN_DIR/lib/LIBSDataCurator.jar" "$@"
EOF

chmod +x release-package/bin/run.sh

# Create README
cat > release-package/README.txt << EOF
LIBSDataCurator $VERSION

This is a self-contained package that includes:
- The application JAR file
- A custom Java Runtime Environment (JRE)
- Configuration files
- Documentation

To run the application:

Linux/macOS:
./bin/run.sh [arguments]

The application includes its own JRE, so you don't need Java installed on your system.

For help with command line arguments:
./bin/run.sh --help
EOF

echo

# Create archive
ARCHIVE_NAME="LIBSDataCurator-${VERSION}-${PLATFORM}.tar.gz"
echo "Creating archive: $ARCHIVE_NAME"
tar -czf "$ARCHIVE_NAME" -C release-package .

echo
echo "=== Build Complete ==="
echo "Archive created: $ARCHIVE_NAME"
echo "Size: $(du -h "$ARCHIVE_NAME" | cut -f1)"
echo
echo "To test the package:"
echo "1. Extract: tar -xzf $ARCHIVE_NAME"
echo "2. Run: ./bin/run.sh --help"
echo
#!/bin/bash
# ============================================================================
# LIBS Instrument Profile Calibration Script
# ============================================================================
# Generates instrument profile from real LIBS measurement data.
# This script is part of the LIBSDataCurator calibration mode.
#
# Usage: ./calibrate.sh -i <sample_csv> -c <composition> [-o <output>] [-n <name>]
#
# Arguments:
#   -i, --input       Path to sample LIBS measurement CSV file (required)
#   -c, --composition Reference material composition, e.g., "Fe-98.0,C-0.5" (required)
#   -o, --output      Output path for profile JSON (default: instrument_profile.json)
#   -n, --name        Instrument name/identifier (default: Unknown)
#   -h, --help        Show this help message
#
# Example:
#   ./calibrate.sh -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5"
# ============================================================================

# Get the directory where this script resides
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"

# Check for custom JRE (self-contained package) or use system Java
if [ -d "$BASE_DIR/jre-custom" ]; then
    JAVA_EXEC="$BASE_DIR/jre-custom/bin/java"
    echo "Using bundled JRE: $JAVA_EXEC"
else
    JAVA_EXEC="java"
    echo "Using system Java"
fi

# Check if Java is available
if ! command -v "$JAVA_EXEC" &> /dev/null; then
    echo "Error: Java not found. Please install Java 21 or higher."
    exit 1
fi

# Find the JAR file
JAR_FILE="$BASE_DIR/lib/LIBSDataCurator.jar"
if [ ! -f "$JAR_FILE" ]; then
    # Try alternative location (when running from build directory)
    JAR_FILE="$BASE_DIR/target/LIBSDataCurator.jar"
    if [ ! -f "$JAR_FILE" ]; then
        # Try build/target location
        JAR_FILE="$BASE_DIR/build/target/LIBSDataCurator.jar"
        if [ ! -f "$JAR_FILE" ]; then
            echo "Error: LIBSDataCurator.jar not found."
            echo "Please run 'mvn package' first to build the application."
            exit 1
        fi
    fi
fi

echo "Using JAR: $JAR_FILE"
echo ""

# Run the instrument profile controller
"$JAVA_EXEC" -cp "$JAR_FILE" com.medals.libsdatagenerator.controller.InstrumentProfileController "$@"

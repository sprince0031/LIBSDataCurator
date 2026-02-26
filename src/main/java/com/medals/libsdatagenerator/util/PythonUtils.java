package com.medals.libsdatagenerator.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for managing a local Python virtual environment and dependencies
 * for generating Jupyter Notebook reports.
 */
public class PythonUtils {

    private static final Logger logger = Logger.getLogger(PythonUtils.class.getName());
    private static PythonUtils instance = null;

    private static final String VENV_DIR_NAME = "python_env";
    private static final List<String> REQUIRED_PACKAGES = Arrays.asList(
            "jupyter", "nbconvert", "pandas", "matplotlib", "numpy"
    );

    public static PythonUtils getInstance() {
        if (instance == null) {
            instance = new PythonUtils();
        }
        return instance;
    }

    /**
     * Checks if Python 3 is installed globally.
     */
    public boolean isPythonInstalled() {
        return checkCommand("python3", "--version") || checkCommand("python", "--version");
    }

    private boolean checkCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Sets up the local Python environment.
     * 1. Checks global python.
     * 2. Creates venv if missing.
     * 3. Installs requirements.
     * 
     * @return true if environment is ready, false otherwise.
     */
    public boolean setupPythonEnvironment() {
        if (!isPythonInstalled()) {
            logger.warning("Python 3 is not installed. Skipping calibration report generation.");
            System.out.println("Please install Python3 and try again to generate the calibration report. Skipping calibration report generation.");
            System.out.println("Instrument profile will still be saved as conf/instrument_profile.json");
            return false;
        }

        Path venvPath = Paths.get(CommonUtils.HOME_PATH, VENV_DIR_NAME);
        if (!Files.exists(venvPath)) {
            logger.info("Creating Python virtual environment at: " + venvPath);
            if (!createVenv(venvPath)) {
                logger.severe("Failed to create virtual environment.");
                return false;
            }
        }

        logger.info("Installing/Verifying Python dependencies in virtual environment...");
        if (!installDependencies(venvPath)) {
            logger.severe("Failed to install Python dependencies.");
            return false;
        }

        return true;
    }

    private boolean createVenv(Path venvPath) {
        String pythonCmd = checkCommand("python3", "--version") ? "python3" : "python";
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "-m", "venv", venvPath.toString());
            pb.inheritIO();
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error creating venv", e);
            return false;
        }
    }

    private boolean installDependencies(Path venvPath) {
        Path pipPath = getVenvExecutable(venvPath, "pip");
        if (pipPath == null) return false;

        List<String> command = new ArrayList<>();
        command.add(pipPath.toString());
        command.add("install");
        command.addAll(REQUIRED_PACKAGES);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error installing dependencies", e);
            return false;
        }
    }

    public Path getVenvJupyterPath() {
        Path venvPath = Paths.get(CommonUtils.HOME_PATH, VENV_DIR_NAME);
        return getVenvExecutable(venvPath, "jupyter");
    }

    private Path getVenvExecutable(Path venvPath, String executableName) {
        // Linux/Mac: bin/, Windows: Scripts/
        Path binPath = venvPath.resolve("bin").resolve(executableName);
        if (Files.exists(binPath)) return binPath;

        Path scriptsPath = venvPath.resolve("Scripts").resolve(executableName + ".exe");
        if (Files.exists(scriptsPath)) return scriptsPath;
        
        // Try without .exe for windows
        scriptsPath = venvPath.resolve("Scripts").resolve(executableName);
        if (Files.exists(scriptsPath)) return scriptsPath;

        return null;
    }
}

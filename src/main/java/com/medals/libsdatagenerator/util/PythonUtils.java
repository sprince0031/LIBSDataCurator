package com.medals.libsdatagenerator.util;

import java.io.FileNotFoundException;
import java.io.IOException;
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
     * @deprecated Legacy Jupyter notebook reporting environment setup is deprecated.
     */
    @Deprecated
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

    /**
     * @deprecated Legacy notebook reporting is deprecated.
     */
    @Deprecated
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

    /**
     * Resolves the path to the GPU calibration engine Python script.
     * Searches in order:
     * 1. Environment variable GPU_CALIBRATION_ENGINE_SCRIPT
     * 2. Direct path: Path.of("scripts", "gpu_calibration_engine.py")
     * 3. Relative to user.dir: Path.of(System.getProperty("user.dir"), "scripts", "gpu_calibration_engine.py")
     * 4. Relative to application/JAR directory
     * 5. Parent directories search (e.g. repo root when running from build/release-package)
     *
     * @return Normalized Path to gpu_calibration_engine.py
     * @throws FileNotFoundException if the script cannot be located
     */
    public Path resolveGpuEngineScriptPath() throws FileNotFoundException {
        // 3. Relative to user.dir
        String userDir = CommonUtils.HOME_PATH;
        if (userDir != null && !userDir.isBlank()) {
            Path p = Path.of(userDir, "scripts", "gpu_calibration_engine.py");
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }

        throw new FileNotFoundException("Could not find gpu_calibration_engine.py in " +
                (userDir != null ? userDir: ".") + "/scripts/gpu_calibration_engine.py, ");
    }

    /**
     * Resolves the path to the nist_lines.py script.
     *
     * @return Normalized Path to nist_lines.py
     * @throws FileNotFoundException if the script cannot be located
     */
    public Path resolveNistLinesScriptPath() throws FileNotFoundException {
        String envScript = System.getenv("NIST_LINES_SCRIPT");
        if (envScript != null && !envScript.isBlank()) {
            Path p = Path.of(envScript);
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        String userDir = CommonUtils.HOME_PATH;
        if (userDir != null && !userDir.isBlank()) {
            Path p = Path.of(userDir, "scripts", "nist_lines.py");
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        Path direct = Path.of("scripts", "nist_lines.py");
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        throw new FileNotFoundException("Could not find nist_lines.py in " +
                (userDir != null ? userDir : ".") + "/scripts/nist_lines.py");
    }

    /**
     * Invokes scripts/nist_lines.py to ensure all ASD line transitions for the given elements
     * are downloaded and cached in cacheDir.
     *
     * @param elementSymbols Chemical elements to fetch (e.g. Fe, Cr, Ni)
     * @param cacheDir Path to data/nist_lines
     * @param minWlNm Minimum wavelength in nm
     * @param maxWlNm Maximum wavelength in nm
     * @throws IOException on I/O error
     * @throws InterruptedException if interrupted while waiting for process
     */
    public void ensureNistLinesCached(List<String> elementSymbols, Path cacheDir, double minWlNm, double maxWlNm)
            throws IOException, InterruptedException {
        if (elementSymbols == null || elementSymbols.isEmpty()) {
            return;
        }
        Path scriptPath = resolveNistLinesScriptPath();
        List<String> command = new ArrayList<>();
        command.add(resolvePythonCommand());
        command.add(scriptPath.toAbsolutePath().toString());
        command.add("--elements");
        command.addAll(elementSymbols);
        command.add("--range");
        command.add(String.valueOf(minWlNm));
        command.add(String.valueOf(maxWlNm));
        command.add("--cache-dir");
        command.add(cacheDir.toAbsolutePath().toString());

        logger.info("Ensuring NIST ASD lines cached: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("nist_lines.py failed with exit code: " + exitCode);
        }
    }

    /**
     * Executes the PyTorch CUDA GPU calibration engine with the generated manifest.
     *
     * @param resolvedScript Path to scripts/gpu_calibration_engine.py
     * @param manifestPath Path to temp/calibration_manifest.json
     * @throws Exception if engine execution fails
     */
    public void runGpuCalibrationEngine(Path resolvedScript, Path manifestPath) throws Exception {

        if (manifestPath == null || !Files.exists(manifestPath)) {
            throw new FileNotFoundException("Calibration manifest not found at: " + manifestPath);
        }

        String pythonCmd = resolvePythonCommand();
        List<String> command = List.of(
                pythonCmd,
                resolvedScript.toString(),
                manifestPath.toAbsolutePath().toString()
        );
        logger.info("Executing GPU Calibration Engine: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("GPU Calibration Engine exited with non-zero exit code: " + exitCode);
        }
    }

    public String resolvePythonCommand() {
        String envPython = System.getenv("PYTHON_EXEC");
        if (envPython != null && Files.exists(Path.of(envPython))) {
            return envPython;
        }
        String venv = System.getenv("VIRTUAL_ENV");
        if (venv != null) {
            Path p = Path.of(venv, "bin", "python3");
            if (Files.exists(p)) return p.toString();
            p = Path.of(venv, "bin", "python");
            if (Files.exists(p)) return p.toString();
        }
        Path phdEnv = Path.of(System.getProperty("user.home"), "Work", "PhD", "phd_code_env", "bin", "python3");
        if (Files.exists(phdEnv)) {
            return phdEnv.toString();
        }
        return checkCommand("python3", "--version") ? "python3" : "python";
    }
}

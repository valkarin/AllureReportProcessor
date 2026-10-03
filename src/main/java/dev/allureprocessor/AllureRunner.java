package dev.allureprocessor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs the allure CLI (Allure 2) to turn a results folder into a report. */
public final class AllureRunner {

    private final String allureCommand;
    private final boolean singleFile;

    /**
     * @param allureCommand "allure" if it is on PATH, or a full path to the allure / allure.bat binary
     */
    public AllureRunner(String allureCommand, boolean singleFile) {
        this.allureCommand = allureCommand;
        this.singleFile = singleFile;
    }

    public void generate(Path resultsDir, Path reportDir) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            // allure on Windows is a .bat file, which ProcessBuilder can only launch through cmd.
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(allureCommand);
        cmd.add("generate");
        cmd.add(resultsDir.toAbsolutePath().toString());
        cmd.add("-o");
        cmd.add(reportDir.toAbsolutePath().toString());
        cmd.add("--clean");
        if (singleFile) {
            cmd.add("--single-file");
        }

        Process process = new ProcessBuilder(cmd).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("allure generate exited with code " + exit + " for " + resultsDir);
        }
    }
}

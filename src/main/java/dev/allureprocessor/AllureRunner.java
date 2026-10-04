package dev.allureprocessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs the allure CLI (Allure 2) to turn a results folder into a report. */
public final class AllureRunner {

    private final String allureCommand;
    private final boolean singleFile;
    private final Map<String, String> options;
    private boolean executableChecked;

    /**
     * @param allureCommand "allure" if it is on PATH, or a full path to the allure / allure.bat binary
     * @param options       extra {@code allure generate} options, flag to value (e.g. {@code --lang} to {@code en});
     *                      entries with a null or blank value are not passed
     */
    public AllureRunner(String allureCommand, boolean singleFile, Map<String, String> options) {
        this.allureCommand = allureCommand;
        this.singleFile = singleFile;
        this.options = options;
    }

    /** @param reportName passed as {@code --name}; not passed when null or blank */
    public void generate(Path resultsDir, Path reportDir, String reportName) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            // allure on Windows is a .bat file, which ProcessBuilder can only launch through cmd.
            cmd.add("cmd.exe");
            cmd.add("/c");
        } else if (!executableChecked) {
            // An allure unpacked from a zip (common on macOS) can lose its execute bit.
            ensureExecutable(locate(allureCommand, System.getenv("PATH")));
            executableChecked = true;
        }
        cmd.add(allureCommand);
        cmd.addAll(generateArgs(resultsDir, reportDir, reportName));

        Process process = new ProcessBuilder(cmd).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("allure generate exited with code " + exit + " for " + resultsDir);
        }
    }

    /** Everything after the allure binary on the command line. */
    List<String> generateArgs(Path resultsDir, Path reportDir, String reportName) {
        List<String> args = new ArrayList<>();
        args.add("generate");
        args.add(resultsDir.toAbsolutePath().toString());
        args.add("-o");
        args.add(reportDir.toAbsolutePath().toString());
        args.add("--clean");
        if (singleFile) {
            args.add("--single-file");
        }
        addOption(args, "--name", reportName);
        options.forEach((flag, value) -> addOption(args, flag, value));
        return args;
    }

    private static void addOption(List<String> args, String flag, String value) {
        if (value != null && !value.isBlank()) {
            args.add(flag);
            args.add(value);
        }
    }

    /**
     * Finds the file the command refers to: the path itself if it has a directory part,
     * otherwise the first executable match on {@code searchPath}, or failing that the first
     * non-executable match (the one worth fixing). Returns null if nothing is found.
     */
    static Path locate(String command, String searchPath) {
        if (command.contains("/")) {
            Path p = Path.of(command);
            return Files.isRegularFile(p) ? p : null;
        }
        if (searchPath == null) {
            return null;
        }
        Path notExecutable = null;
        for (String dir : searchPath.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path p;
            try {
                p = Path.of(dir, command);
            } catch (InvalidPathException e) {
                continue;
            }
            if (!Files.isRegularFile(p)) {
                continue;
            }
            if (Files.isExecutable(p)) {
                return p;
            }
            if (notExecutable == null) {
                notExecutable = p;
            }
        }
        return notExecutable;
    }

    /** Adds the execute bit if it is missing, like {@code chmod +x}. Does nothing for null or an executable file. */
    static void ensureExecutable(Path binary) throws IOException {
        if (binary == null || Files.isExecutable(binary)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(binary));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            if (perms.contains(PosixFilePermission.GROUP_READ)) {
                perms.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(binary, perms);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IOException("Allure binary is not executable and could not be made executable: " + binary
                    + ". Run: chmod +x " + binary, e);
        }
        System.err.println("WARN Allure binary was not executable, added execute permission: " + binary);
    }
}

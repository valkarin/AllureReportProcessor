package dev.allureprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Usage:
 * <pre>
 * java -jar allure-results-processor.jar --results target/allure-results --out target/allure-split
 *      [--allure path/to/allure] [--no-split] [--no-single-file] [--skip-generate]
 *      [--name text] [--lang code] [--config file] [--configDirectory dir] [--profile name]
 *      [--report-file name]
 * </pre>
 * Output layout:
 * <pre>
 * out/results/passed       out/passed/index.html
 * out/results/not-passed   out/not-passed/index.html   (failed, broken, skipped, unknown, ...)
 * </pre>
 * With {@code --no-split}, everything goes to {@code out/results/all} and {@code out/all/index.html} instead.
 * {@code --report-file} renames index.html.
 * With {@code --no-single-file}, each report is a folder: {@code out/report/<bucket>}.
 */
public final class Main {

    /** Output folder name used with {@code --no-split}. */
    static final String ALL_FOLDER = "all";

    static final String DEFAULT_REPORT_FILE = "index.html";

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);

        // Register your modifiers here. They run in order on every result before it is written.
        List<ResultModifier> modifiers = new ArrayList<>();
        // modifiers.add(new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue"));

        // Keep this last so it also removes duplicate links created by the modifiers above.
        modifiers.add(new dev.allureprocessor.modifiers.DedupeLinksModifier());

        int exit = run(options, modifiers);
        System.exit(exit);
    }

    static int run(Options options, List<ResultModifier> modifiers) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultsFolder folder = ResultsFolder.load(options.results, mapper);
        folder.warnings().forEach(w -> System.err.println("WARN " + w));

        Map<Bucket, BucketContent> buckets = Splitter.split(folder);
        BucketWriter writer = new BucketWriter(mapper);
        AllureRunner runner = new AllureRunner(options.allure, options.singleFile, options.allureOptions());
        Map<String, byte[]> allNewFiles = new TreeMap<>();

        for (Map.Entry<Bucket, BucketContent> e : buckets.entrySet()) {
            Bucket bucket = e.getKey();
            BucketContent content = e.getValue();

            ModifierContext context = new ModifierContext(bucket, folder);
            for (ObjectNode result : content.results().values()) {
                for (ResultModifier m : modifiers) {
                    m.modify(result, context);
                }
            }
            // Pick up attachments that modifiers added to results.
            content.results().values().forEach(r -> Splitter.collectAttachmentSources(r, content.attachmentSources()));

            if (options.split) {
                writeAndGenerate(options, writer, runner, folder, bucket.folderName(), content, context.newFiles());
            } else {
                allNewFiles.putAll(context.newFiles());
            }
        }
        if (!options.split) {
            // Modifiers still saw each result's own bucket above; only the output is combined.
            writeAndGenerate(options, writer, runner, folder, ALL_FOLDER,
                    Splitter.merge(buckets.values(), folder), allNewFiles);
        }
        return 0;
    }

    private static void writeAndGenerate(Options options, BucketWriter writer, AllureRunner runner, ResultsFolder folder,
                                         String folderName, BucketContent content, Map<String, byte[]> newFiles)
            throws Exception {
        Path resultsDir = options.out.resolve("results").resolve(folderName);
        // A single-file report is one HTML file in <out>/<bucket>; only a multi-file report needs a report folder.
        Path reportDir = options.singleFile ? null : options.out.resolve("report").resolve(folderName);
        writer.write(content, newFiles, folder, resultsDir, reportDir)
                .forEach(w -> System.err.println("WARN [" + folderName + "] " + w));

        System.out.printf("%-10s %4d results, %3d containers, %3d attachments -> %s%n",
                folderName, content.results().size(), content.containers().size(),
                content.attachmentSources().size(), resultsDir);

        if (options.skipGenerate) {
            return;
        }
        String reportName = options.reportNameFor(folderName);
        if (options.singleFile) {
            runner.generateFile(resultsDir, options.out.resolve(folderName).resolve(options.reportFileFor(folderName)), reportName);
        } else {
            runner.generate(resultsDir, reportDir, reportName);
        }
    }

    static final class Options {
        Path results;
        Path out;
        String allure = "allure";
        boolean split = true;
        boolean singleFile = true;
        boolean skipGenerate = false;
        String reportName;
        String reportLanguage;
        String config;
        String configDirectory;
        String profile;
        String reportFile;

        /** The single-file report's file name for one output folder; index.html unless --report-file is set. */
        String reportFileFor(String folderName) {
            String template = reportFile == null || reportFile.isBlank() ? DEFAULT_REPORT_FILE : reportFile;
            return template.replace("{bucket}", folderName);
        }

        /** The report name for one output folder: {@code {bucket}} is replaced by passed, not-passed or all. */
        String reportNameFor(String folderName) {
            return reportName == null ? null : reportName.replace("{bucket}", folderName);
        }

        /** Options handed to allure generate as they are; null or blank ones are left out by the runner. */
        Map<String, String> allureOptions() {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("--lang", reportLanguage);
            o.put("--config", config);
            o.put("--configDirectory", configDirectory);
            o.put("--profile", profile);
            return o;
        }

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--results" -> o.results = Path.of(value(args, ++i, "--results"));
                    case "--out" -> o.out = Path.of(value(args, ++i, "--out"));
                    case "--allure" -> o.allure = value(args, ++i, "--allure");
                    case "--no-split" -> o.split = false;
                    case "--no-single-file" -> o.singleFile = false;
                    case "--skip-generate" -> o.skipGenerate = true;
                    case "--name", "--report-name" -> o.reportName = value(args, ++i, args[i - 1]);
                    case "--lang", "--report-language" -> o.reportLanguage = value(args, ++i, args[i - 1]);
                    case "--config" -> o.config = value(args, ++i, "--config");
                    case "--configDirectory" -> o.configDirectory = value(args, ++i, "--configDirectory");
                    case "--profile" -> o.profile = value(args, ++i, "--profile");
                    case "--report-file" -> o.reportFile = value(args, ++i, "--report-file");
                    default -> usage("Unknown argument: " + args[i]);
                }
            }
            if (o.results == null || o.out == null) {
                usage("--results and --out are required");
            }
            if (o.reportFile != null && !o.reportFile.isBlank()) {
                if (!o.singleFile) {
                    usage("--report-file cannot be used with --no-single-file");
                }
                if (o.reportFile.contains("/") || o.reportFile.contains("\\")) {
                    usage("--report-file must be a file name, not a path");
                }
            }
            return o;
        }

        private static String value(String[] args, int i, String flag) {
            if (i >= args.length) {
                usage("Missing value for " + flag);
            }
            return args[i];
        }

        private static void usage(String error) {
            System.err.println(error);
            System.err.println("Usage: java -jar allure-results-processor.jar --results <dir> --out <dir> "
                    + "[--allure <path>] [--no-split] [--no-single-file] [--skip-generate] "
                    + "[--name <text>] [--lang <code>] [--config <file>] [--configDirectory <dir>] [--profile <name>] "
                    + "[--report-file <name>]");
            System.exit(2);
        }
    }
}

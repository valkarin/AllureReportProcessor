package dev.allureprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.allureprocessor.modifiers.DedupeLinksModifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the processor from Java code, e.g. from a JUnit Platform {@code @AfterSuite} method:
 * <pre>
 * AllureProcessor.builder()
 *         .results(Path.of("target/allure-results"))
 *         .out(Path.of("target/allure-split"))
 *         .addModifier(new LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue"))
 *         .run();
 * </pre>
 * Output layout:
 * <pre>
 * out/results/passed       out/passed/index.html
 * out/results/not-passed   out/not-passed/index.html   (failed, broken, skipped, unknown, ...)
 * </pre>
 * Without splitting, everything goes to {@code out/results/all} and {@code out/all/index.html} instead.
 * A multi-file report is a folder: {@code out/report/<bucket>}.
 */
public final class AllureProcessor {

    /** Output folder name used when not splitting. */
    static final String ALL_FOLDER = "all";

    static final String DEFAULT_REPORT_FILE = "index.html";

    private AllureProcessor() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The built-in modifiers. Within a {@link ResultModifier#phase() phase} they run after
     * the ones added with {@link Builder#addModifier}.
     */
    public static List<ResultModifier> defaultModifiers() {
        return List.of(new DedupeLinksModifier());
    }

    public static final class Builder {
        private Path results;
        private Path out;
        private String allure = "allure";
        private boolean split = true;
        private boolean singleFile = true;
        private boolean skipGenerate = false;
        private String reportName;
        private String reportLanguage;
        private String config;
        private String configDirectory;
        private String profile;
        private String reportFile;
        private final List<ResultModifier> modifiers = new ArrayList<>();
        private boolean defaultModifiers = true;

        private Builder() {
        }

        /** Source allure-results folder (required). It is never modified. */
        public Builder results(Path results) {
            this.results = results;
            return this;
        }

        /** Output root (required). */
        public Builder out(Path out) {
            this.out = out;
            return this;
        }

        /** Allure binary, default "allure" (on PATH). */
        public Builder allure(String allure) {
            this.allure = allure;
            return this;
        }

        /** Whether to write separate passed / not-passed reports (default) or one combined report. */
        public Builder split(boolean split) {
            this.split = split;
            return this;
        }

        /** Whether each report is one HTML file (default) or a multi-file report folder. */
        public Builder singleFile(boolean singleFile) {
            this.singleFile = singleFile;
            return this;
        }

        /** Only write the results folders, don't call Allure. */
        public Builder skipGenerate(boolean skipGenerate) {
            this.skipGenerate = skipGenerate;
            return this;
        }

        /** Report title; {@code {bucket}} is replaced by passed, not-passed or all. */
        public Builder reportName(String reportName) {
            this.reportName = reportName;
            return this;
        }

        /** Report language, e.g. "en". */
        public Builder reportLanguage(String reportLanguage) {
            this.reportLanguage = reportLanguage;
            return this;
        }

        /** Allure config file listing the plugins to load; overrides configDirectory and profile. */
        public Builder config(String config) {
            this.config = config;
            return this;
        }

        public Builder configDirectory(String configDirectory) {
            this.configDirectory = configDirectory;
            return this;
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        /**
         * File name of the single-file report, default index.html; {@code {bucket}} is replaced
         * by passed, not-passed or all.
         */
        public Builder reportFile(String reportFile) {
            this.reportFile = reportFile;
            return this;
        }

        /**
         * Adds a modifier. Modifiers run by {@link ResultModifier#phase() phase}, and within a phase in the
         * order added, before the {@link #defaultModifiers() built-in} ones.
         */
        public Builder addModifier(ResultModifier modifier) {
            modifiers.add(modifier);
            return this;
        }

        /** Runs only the modifiers added with {@link #addModifier}. */
        public Builder withoutDefaultModifiers() {
            this.defaultModifiers = false;
            return this;
        }

        /** @throws IllegalArgumentException if a required setting is missing or settings contradict each other */
        public void run() throws IOException, InterruptedException {
            validate();
            process(this);
        }

        void validate() {
            if (results == null || out == null) {
                throw new IllegalArgumentException("The results folder and the output folder are required (--results, --out)");
            }
            if (reportFile != null && !reportFile.isBlank()) {
                if (!singleFile) {
                    throw new IllegalArgumentException("A report file name (--report-file) cannot be combined with a multi-file report (--no-single-file)");
                }
                if (reportFile.contains("/") || reportFile.contains("\\")) {
                    throw new IllegalArgumentException("The report file (--report-file) must be a file name, not a path");
                }
            }
        }

        List<ResultModifier> allModifiers() {
            List<ResultModifier> all = new ArrayList<>(modifiers);
            if (defaultModifiers) {
                all.addAll(defaultModifiers());
            }
            // The sort is stable, so the order above is kept within each phase.
            all.sort(Comparator.comparing(ResultModifier::phase));
            return all;
        }

        /** The single-file report's file name for one output folder. */
        String reportFileFor(String folderName) {
            String template = reportFile == null || reportFile.isBlank() ? DEFAULT_REPORT_FILE : reportFile;
            return template.replace("{bucket}", folderName);
        }

        /** The report name for one output folder. */
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
    }

    private static void process(Builder options) throws IOException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();
        ResultsFolder folder = ResultsFolder.load(options.results, mapper);
        folder.warnings().forEach(w -> System.err.println("WARN " + w));

        List<ResultModifier> modifiers = options.allModifiers();
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
    }

    private static void writeAndGenerate(Builder options, BucketWriter writer, AllureRunner runner, ResultsFolder folder,
                                         String folderName, BucketContent content, Map<String, byte[]> newFiles)
            throws IOException, InterruptedException {
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
}

package dev.allureprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Usage:
 * <pre>
 * java -jar allure-results-processor.jar --results target/allure-results --out target/allure-split
 *      [--allure path/to/allure] [--no-single-file] [--skip-generate]
 * </pre>
 * Output layout:
 * <pre>
 * out/results/passed       out/report/passed
 * out/results/not-passed   out/report/not-passed   (failed, broken, skipped, unknown, ...)
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);

        // Register your modifiers here. They run in order on every result before it is written.
        List<ResultModifier> modifiers = new ArrayList<>();
        // modifiers.add(new dev.allureprocessor.modifiers.AddLabelModifier(Bucket.NOT_PASSED, "tag", "needs-triage"));

        int exit = run(options, modifiers);
        System.exit(exit);
    }

    static int run(Options options, List<ResultModifier> modifiers) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultsFolder folder = ResultsFolder.load(options.results, mapper);
        folder.warnings().forEach(w -> System.err.println("WARN " + w));

        Map<Bucket, BucketContent> buckets = Splitter.split(folder);
        BucketWriter writer = new BucketWriter(mapper);
        AllureRunner runner = new AllureRunner(options.allure, options.singleFile);

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

            Path resultsDir = options.out.resolve("results").resolve(bucket.folderName());
            Path reportDir = options.out.resolve("report").resolve(bucket.folderName());
            writer.write(content, context.newFiles(), folder, resultsDir, reportDir)
                    .forEach(w -> System.err.println("WARN [" + bucket.folderName() + "] " + w));

            System.out.printf("%-10s %4d results, %3d containers, %3d attachments -> %s%n",
                    bucket.folderName(), content.results().size(), content.containers().size(),
                    content.attachmentSources().size(), resultsDir);

            if (!options.skipGenerate) {
                runner.generate(resultsDir, reportDir);
            }
        }
        return 0;
    }

    static final class Options {
        Path results;
        Path out;
        String allure = "allure";
        boolean singleFile = true;
        boolean skipGenerate = false;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--results" -> o.results = Path.of(value(args, ++i, "--results"));
                    case "--out" -> o.out = Path.of(value(args, ++i, "--out"));
                    case "--allure" -> o.allure = value(args, ++i, "--allure");
                    case "--no-single-file" -> o.singleFile = false;
                    case "--skip-generate" -> o.skipGenerate = true;
                    default -> usage("Unknown argument: " + args[i]);
                }
            }
            if (o.results == null || o.out == null) {
                usage("--results and --out are required");
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
                    + "[--allure <path>] [--no-single-file] [--skip-generate]");
            System.exit(2);
        }
    }
}

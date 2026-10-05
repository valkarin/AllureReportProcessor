package dev.allureprocessor;

import java.nio.file.Path;

/**
 * Command line front end for {@link AllureProcessor}. Usage:
 * <pre>
 * java -jar allure-results-processor-all.jar --results target/allure-results --out target/allure-split
 *      [--allure path/to/allure] [--no-split] [--no-single-file] [--skip-generate]
 *      [--name text] [--lang code] [--config file] [--configDirectory dir] [--profile name]
 *      [--report-file name]
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        AllureProcessor.Builder processor;
        try {
            processor = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: java -jar allure-results-processor-all.jar --results <dir> --out <dir> "
                    + "[--allure <path>] [--no-split] [--no-single-file] [--skip-generate] "
                    + "[--name <text>] [--lang <code>] [--config <file>] [--configDirectory <dir>] [--profile <name>] "
                    + "[--report-file <name>]");
            System.exit(2);
            return;
        }

        // Register your modifiers here. They run on every result, ordered by phase and then by the order added.
        // processor.addModifier(new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue"));

        processor.run();
    }

    /** @throws IllegalArgumentException for an unknown argument, a missing value, or settings that contradict each other */
    static AllureProcessor.Builder parse(String[] args) {
        AllureProcessor.Builder b = AllureProcessor.builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--results" -> b.results(Path.of(value(args, ++i)));
                case "--out" -> b.out(Path.of(value(args, ++i)));
                case "--allure" -> b.allure(value(args, ++i));
                case "--no-split" -> b.split(false);
                case "--no-single-file" -> b.singleFile(false);
                case "--skip-generate" -> b.skipGenerate(true);
                case "--name", "--report-name" -> b.reportName(value(args, ++i));
                case "--lang", "--report-language" -> b.reportLanguage(value(args, ++i));
                case "--config" -> b.config(value(args, ++i));
                case "--configDirectory" -> b.configDirectory(value(args, ++i));
                case "--profile" -> b.profile(value(args, ++i));
                case "--report-file" -> b.reportFile(value(args, ++i));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        b.validate();
        return b;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }
}

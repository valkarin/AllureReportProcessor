# Allure Results Processor

Pre-processes an `allure-results` folder before the Allure CLI renders it:

1. Reads every `*-result.json` and `*-container.json` as raw JSON (unknown fields are preserved).
2. Runs your **modifiers** on each result to add or change data Allure will render.
3. Splits results into **passed** and **not-passed** (failed, broken, skipped, unknown, or anything else).
4. Writes each bucket to its own results folder with the containers and attachments it needs.
5. Runs `allure generate --single-file` on each bucket.

The original results folder is never modified.

## Requirements

- Java 17+
- Maven 3.8+
- Allure 2 CLI (`allure --version`), on `PATH` or passed with `--allure`

## Build and run

```bash
mvn package
java -jar target/allure-results-processor-0.1.0-SNAPSHOT-all.jar \
  --results target/allure-results \
  --out target/allure-split
```

Options:

| Flag | Meaning |
|---|---|
| `--results <dir>` | Source allure-results folder (required) |
| `--out <dir>` | Output root (required); it is rebuilt on every run |
| `--allure <path>` | Allure binary, default `allure` (on Windows, e.g. `C:\tools\allure\bin\allure.bat`). On macOS / Linux, if the binary is missing its execute bit, it is added automatically |
| `--no-split` | Write one combined results folder and report instead of passed / not-passed. Modifiers still run, and `ctx.bucket()` still tells them whether each result passed |
| `--no-single-file` | Generate a normal multi-file report folder (`<out>/report/<bucket>/`) instead of one HTML file |
| `--skip-generate` | Only write the split results folders, don't call Allure |
| `--name <text>` | Report title (alias `--report-name`). `{bucket}` is replaced by `passed`, `not-passed` or `all`, e.g. `--name "Nightly ({bucket})"` |
| `--lang <code>` | Report language, e.g. `en`, `de` (alias `--report-language`) |
| `--config <file>` | Allure config file listing the plugins to load; overrides the two below |
| `--configDirectory <dir>` | Directory holding the Allure config, default `ALLURE_HOME/config` |
| `--profile <name>` | Use `allure-<name>.yml` from the config directory instead of `allure.yml` |
| `--report-file <name>` | File name of the single-file report, default `index.html`. `{bucket}` is replaced by `passed`, `not-passed` or `all`, e.g. `--report-file "nightly-{bucket}.html"`. Not allowed with `--no-single-file` |

`--name`, `--lang` and the three config options are handed to `allure generate` unchanged and are left out when not given or empty. `--name` and `--lang` need a recent Allure 2 (checked against 2.38); older versions reject them as unknown options.

Output:

```
<out>/results/passed       <out>/passed/index.html
<out>/results/not-passed   <out>/not-passed/index.html
```

With `--no-split`:

```
<out>/results/all          <out>/all/index.html
```

With `--no-single-file`, each report is a folder instead: `<out>/report/<bucket>/index.html`.

## Use from Java (e.g. a JUnit suite)

The build also produces a plain `allure-results-processor-0.1.0-SNAPSHOT.jar` (without bundled dependencies) to use as a Maven dependency; `mvn install` puts it in your local repository:

```xml
<dependency>
  <groupId>dev.allureprocessor</groupId>
  <artifactId>allure-results-processor</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

`AllureProcessor.builder()` has a method for every command line option. For example, after a Cucumber suite on the JUnit Platform (`@AfterSuite` needs `junit-platform-suite` 1.11+):

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class RunCucumberTest {

    @AfterSuite
    static void processAllureResults() throws Exception {
        AllureProcessor.builder()
                .results(Path.of("target/allure-results"))
                .out(Path.of("target/allure-split"))
                .reportName("Nightly ({bucket})")
                .addModifier(new LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue"))
                .run();
    }
}
```

`run()` throws if Allure fails, which fails the suite; catch the exception there if a reporting problem should not break the build. The Allure CLI must be installed where the tests run.

## How the split works

- **Retries.** Attempts of the same test share a `historyId`. The latest attempt (highest `stop`) decides the bucket, and all attempts go to that bucket together, so Allure still shows them on the Retries tab. A scenario that failed and then passed on retry appears only in the passed report.
- **Containers.** A container (Cucumber hooks live here) is included in every bucket that holds one of its children, resolved through nested containers. Shared containers are copied with `children` trimmed to that bucket.
- **Attachments.** Only files referenced by the bucket's results, steps (at any depth), and fixtures are copied. Missing files are reported as warnings.
- **Shared files.** `categories.json`, `environment.properties`, `environment.xml`, and `executor.json` are copied into every bucket.
- **History.** If `<out>/report/<bucket>/history` exists from a previous run, it is copied into the bucket's results so trends continue. Note: single-file reports are one HTML file with no `history/` folder, so use `--no-single-file` if you want trend charts across runs.
- **Determinism.** Files are read and written in sorted order; the same input always produces byte-identical output folders.

## Adding your own data

Implement `ResultModifier` and add it with `addModifier`, on the builder or (for the command line) in `Main.main`:

```java
processor.addModifier((result, ctx) -> {
    if (ctx.bucket() == Bucket.NOT_PASSED) {
        AllureJson.addLabel(result, "tag", "needs-triage");
        AllureJson.addLink(result, "Runbook", "https://wiki/runbook", "link");
        AllureJson.appendDescriptionHtml(result, "<b>Owner:</b> payments team");

        String source = ctx.addAttachmentFile("extra diagnostics", "txt");
        AllureJson.addAttachment(result, "Diagnostics", "text/plain", source);
    }
});
```

Fields Allure renders that are useful to change: `labels` (tags, feature, story, epic, severity, owner, `parentSuite` / `suite` / `subSuite` to regroup the Suites tree), `links`, `parameters`, `description` / `descriptionHtml`, `attachments`, and `statusDetails` (`message`, `trace`).

Link helpers: `addLink`, `dedupeLinks`.

Label helpers: `label`, `labels`, `addLabel`, `setLabel`, `removeLabel(name)`, `removeLabel(name, value)`, `removeLabelsIf(predicate)`.

Ready-made modifiers in `dev.allureprocessor.modifiers`:

- `LabelToLinkModifier`: if a label is present, removes it and adds a link per value, e.g.
  `new LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue")`
  turns `jira=PAY-123` into an issue link named PAY-123.
- `DedupeLinksModifier`: removes links with the same name and url as an earlier one, keeping the first (its type wins). Built in: it always runs, in the `LATE` phase, so it also catches duplicates created by other modifiers. Also available as `AllureJson.dedupeLinks(result)`.

**Order.** Each modifier has a phase: `EARLY`, `NORMAL` (the default) or `LATE`. Phases run in that order, and a later modifier sees the changes of an earlier one. Within a phase, modifiers run in the order they are added, with yours before the built-in ones (`AllureProcessor.defaultModifiers()`; `withoutDefaultModifiers()` on the builder turns those off). A modifier class picks its phase by overriding `phase()`:

```java
@Override
public Phase phase() {
    return Phase.LATE;   // clean-up that must see what the other modifiers did
}
```

## Tests

```bash
mvn test
```

Covers retry grouping, link deduplication, nested and shared containers, attachment discovery in nested steps and fixtures, malformed files, preservation of unknown fields, modifiers, and deterministic output.

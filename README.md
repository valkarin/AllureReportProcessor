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
java -jar target/allure-results-processor-0.1.0-SNAPSHOT.jar \
  --results target/allure-results \
  --out target/allure-split
```

Options:

| Flag | Meaning |
|---|---|
| `--results <dir>` | Source allure-results folder (required) |
| `--out <dir>` | Output root (required); it is rebuilt on every run |
| `--allure <path>` | Allure binary, default `allure` (on Windows, e.g. `C:\tools\allure\bin\allure.bat`) |
| `--no-single-file` | Generate a normal multi-file report instead of one `index.html` |
| `--skip-generate` | Only write the split results folders, don't call Allure |

Output:

```
<out>/results/passed       <out>/report/passed/index.html
<out>/results/not-passed   <out>/report/not-passed/index.html
```

## How the split works

- **Retries.** Attempts of the same test share a `historyId`. The latest attempt (highest `stop`) decides the bucket, and all attempts go to that bucket together, so Allure still shows them on the Retries tab. A scenario that failed and then passed on retry appears only in the passed report.
- **Containers.** A container (Cucumber hooks live here) is included in every bucket that holds one of its children, resolved through nested containers. Shared containers are copied with `children` trimmed to that bucket.
- **Attachments.** Only files referenced by the bucket's results, steps (at any depth), and fixtures are copied. Missing files are reported as warnings.
- **Shared files.** `categories.json`, `environment.properties`, `environment.xml`, and `executor.json` are copied into every bucket.
- **History.** If `<out>/report/<bucket>/history` exists from a previous run, it is copied into the bucket's results so trends continue. Note: `--single-file` reports are one `index.html` with no `history/` folder, so use `--no-single-file` if you want trend charts across runs.
- **Determinism.** Files are read and written in sorted order; the same input always produces byte-identical output folders.

## Adding your own data

Implement `ResultModifier` and register it in `Main.main`:

```java
modifiers.add((result, ctx) -> {
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

Label helpers: `label`, `labels`, `addLabel`, `setLabel`, `removeLabel(name)`, `removeLabel(name, value)`, `removeLabelsIf(predicate)`.

Ready-made modifiers in `dev.allureprocessor.modifiers`:

- `AddLabelModifier`: adds a label to every result in one bucket.
- `LabelToLinkModifier`: if a label is present, removes it and adds a link per value, e.g.
  `new LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue")`
  turns `jira=PAY-123` into an issue link named PAY-123.

Modifiers run in the order they are registered, so a later modifier sees the changes of an earlier one.

## Tests

```bash
mvn test
```

Covers retry grouping, nested and shared containers, attachment discovery in nested steps and fixtures, malformed files, preservation of unknown fields, modifiers, and deterministic output.

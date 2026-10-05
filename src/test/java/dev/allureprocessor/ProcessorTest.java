package dev.allureprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tmp;
    Path results;

    @BeforeEach
    void createResults() throws IOException {
        results = Files.createDirectories(tmp.resolve("allure-results"));

        result("r1", "h1", "passed", 100, "");
        result("r2", "h2", "failed", 200,
                ",\"attachments\":[{\"name\":\"shot\",\"source\":\"a1-attachment.png\",\"type\":\"image/png\"}]"
                        + ",\"steps\":[{\"name\":\"When I log in\",\"status\":\"failed\",\"steps\":[{\"name\":\"nested\","
                        + "\"attachments\":[{\"name\":\"log\",\"source\":\"a2-attachment.txt\",\"type\":\"text/plain\"}]}]}]");
        // h3: broken first, passed on retry -> passed bucket, both attempts kept together
        result("r3", "h3", "broken", 300, "");
        result("r3b", "h3", "passed", 400, "");
        // h5: passed first, failed on retry -> failed bucket
        result("r5", "h5", "passed", 300, "");
        result("r5b", "h5", "failed", 500, "");
        result("r4", "h4", "skipped", 100, "");
        // field unknown to any Allure model version must survive the round trip
        result("r6", "h6", "broken", 100, ",\"someFutureField\":{\"x\":1}");

        write("c1-container.json", "{\"uuid\":\"c1\",\"children\":[\"r2\"],\"befores\":[{\"name\":\"Before hook\","
                + "\"attachments\":[{\"name\":\"setup\",\"source\":\"a3-attachment.txt\",\"type\":\"text/plain\"}]}]}");
        write("c2-container.json", "{\"uuid\":\"c2\",\"children\":[\"r1\",\"r2\"]}");
        // nested: c3 only references c1, so it belongs wherever c1 belongs
        write("c3-container.json", "{\"uuid\":\"c3\",\"children\":[\"c1\"]}");

        write("a1-attachment.png", "png");
        write("a2-attachment.txt", "log");
        write("a3-attachment.txt", "setup");
        write("unused-attachment.txt", "nobody points at me");
        write("categories.json", "[]");
        write("broken-result.json", "{ not json");
    }

    @Test
    void splitsByLatestAttemptAndKeepsRetriesTogether() throws IOException {
        Map<Bucket, BucketContent> buckets = Splitter.split(ResultsFolder.load(results, mapper));

        assertEquals(Set.of("r2", "r4", "r5", "r5b", "r6"), buckets.get(Bucket.NOT_PASSED).results().keySet());
        assertEquals(Set.of("r1", "r3", "r3b"), buckets.get(Bucket.PASSED).results().keySet());
        assertEquals(2, buckets.size());
    }

    @Test
    void includesContainersTransitivelyAndTrimsSharedChildren() throws IOException {
        Map<Bucket, BucketContent> buckets = Splitter.split(ResultsFolder.load(results, mapper));

        BucketContent failed = buckets.get(Bucket.NOT_PASSED);
        assertEquals(Set.of("c1", "c2", "c3"), failed.containers().keySet());
        assertEquals(List.of("r2"), children(failed.containers().get("c2")));

        BucketContent passed = buckets.get(Bucket.PASSED);
        assertEquals(Set.of("c2"), passed.containers().keySet());
        assertEquals(List.of("r1"), children(passed.containers().get("c2")));
    }

    @Test
    void collectsAttachmentsFromNestedStepsAndFixtures() throws IOException {
        Map<Bucket, BucketContent> buckets = Splitter.split(ResultsFolder.load(results, mapper));

        assertEquals(Set.of("a1-attachment.png", "a2-attachment.txt", "a3-attachment.txt"),
                buckets.get(Bucket.NOT_PASSED).attachmentSources());
        assertTrue(buckets.get(Bucket.PASSED).attachmentSources().isEmpty());
    }

    @Test
    void skipsMalformedFilesWithWarning() throws IOException {
        ResultsFolder folder = ResultsFolder.load(results, mapper);
        assertEquals(8, folder.results().size());
        assertEquals(1, folder.warnings().size());
    }

    @Test
    void endToEndWritesFoldersAppliesModifiersAndLeavesSourceUntouched() throws Exception {
        Path out = tmp.resolve("split");
        AllureProcessor.Builder processor = AllureProcessor.builder().results(results).out(out).skipGenerate(true);

        ResultModifier addReport = (result, ctx) -> {
            if (ctx.bucket() == Bucket.NOT_PASSED) {
                String source = ctx.addAttachmentFile("triage notes", "txt");
                AllureJson.addAttachment(result, "Triage", "text/plain", source);
                AllureJson.appendDescriptionHtml(result, "<b>Owner:</b> payments");
            }
        };
        ResultModifier addTag = (result, ctx) -> {
            if (ctx.bucket() == Bucket.NOT_PASSED) {
                AllureJson.addLabel(result, "tag", "needs-triage");
            }
        };
        processor.addModifier(addTag).addModifier(addReport).run();

        Path failed = out.resolve("results/not-passed");
        Set<String> files = list(failed);
        assertTrue(files.containsAll(Set.of("r2-result.json", "c1-container.json", "c3-container.json",
                "a1-attachment.png", "a2-attachment.txt", "a3-attachment.txt", "categories.json")));
        assertFalse(files.contains("unused-attachment.txt"));
        assertFalse(files.contains("r1-result.json"));

        ObjectNode r2 = (ObjectNode) mapper.readTree(failed.resolve("r2-result.json").toFile());
        assertEquals("needs-triage", AllureJson.label(r2, "tag"));
        assertEquals("<b>Owner:</b> payments", r2.path("descriptionHtml").asText());
        String triageSource = r2.path("attachments").get(1).path("source").asText();
        assertEquals("triage notes", Files.readString(failed.resolve(triageSource)));

        ObjectNode r6 = (ObjectNode) mapper.readTree(failed.resolve("r6-result.json").toFile());
        assertEquals(1, r6.path("someFutureField").path("x").asInt());

        ObjectNode r1 = (ObjectNode) mapper.readTree(out.resolve("results/passed/r1-result.json").toFile());
        assertEquals(null, AllureJson.label(r1, "tag"));

        ObjectNode original = (ObjectNode) mapper.readTree(results.resolve("r2-result.json").toFile());
        assertEquals(null, AllureJson.label(original, "tag"));
    }

    @Test
    void noSplitWritesOneFolderAndModifiersStillSeeEachResultsBucket() throws Exception {
        Path out = tmp.resolve("unsplit");
        AllureProcessor.Builder processor = Main.parse(new String[] {
                "--results", results.toString(), "--out", out.toString(), "--no-split", "--skip-generate"});

        ResultModifier triage = (result, ctx) -> {
            if (ctx.bucket() == Bucket.NOT_PASSED) {
                AllureJson.addLabel(result, "tag", "needs-triage");
                AllureJson.addAttachment(result, "Triage", "text/plain", ctx.addAttachmentFile("triage notes", "txt"));
            }
        };
        processor.addModifier(triage).run();

        assertEquals(Set.of("all"), list(out.resolve("results")));
        Path all = out.resolve("results/all");
        Set<String> files = list(all);
        assertTrue(files.containsAll(Set.of("r1-result.json", "r2-result.json", "r3-result.json", "r3b-result.json",
                "r4-result.json", "r5-result.json", "r5b-result.json", "r6-result.json",
                "c1-container.json", "c2-container.json", "c3-container.json",
                "a1-attachment.png", "a2-attachment.txt", "a3-attachment.txt", "categories.json")));
        assertFalse(files.contains("unused-attachment.txt"));

        // shared container is whole again, not trimmed to one bucket
        assertEquals(List.of("r1", "r2"), children(mapper.readTree(all.resolve("c2-container.json").toFile())));

        ObjectNode r2 = (ObjectNode) mapper.readTree(all.resolve("r2-result.json").toFile());
        assertEquals("needs-triage", AllureJson.label(r2, "tag"));
        String triageSource = r2.path("attachments").get(1).path("source").asText();
        assertEquals("triage notes", Files.readString(all.resolve(triageSource)));

        ObjectNode r1 = (ObjectNode) mapper.readTree(all.resolve("r1-result.json").toFile());
        assertEquals(null, AllureJson.label(r1, "tag"));
    }

    @Test
    void outputIsDeterministic() throws Exception {
        AllureProcessor.Builder processor = AllureProcessor.builder().results(results).skipGenerate(true);
        processor.out(tmp.resolve("run1")).run();
        processor.out(tmp.resolve("run2")).run();

        for (String bucket : List.of("passed", "not-passed")) {
            Path a = tmp.resolve("run1/results").resolve(bucket);
            Path b = tmp.resolve("run2/results").resolve(bucket);
            assertEquals(list(a), list(b));
            for (String f : list(a)) {
                assertEquals(Files.readString(a.resolve(f)), Files.readString(b.resolve(f)), f);
            }
        }
    }

    @Test
    void labelToLinkReplacesLabelWithLinks() throws IOException {
        ObjectNode r = (ObjectNode) mapper.readTree("{\"labels\":["
                + "{\"name\":\"jira\",\"value\":\"PAY-1\"},"
                + "{\"name\":\"feature\",\"value\":\"Login\"},"
                + "{\"name\":\"jira\",\"value\":\"PAY-2\"}],"
                + "\"links\":[{\"name\":\"existing\",\"url\":\"https://x\",\"type\":\"link\"}]}");

        new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://jira.example.com/browse/{value}", "issue")
                .modify(r, new ModifierContext(Bucket.NOT_PASSED, null));

        assertTrue(AllureJson.labels(r, "jira").isEmpty());
        assertEquals(List.of("Login"), AllureJson.labels(r, "feature"));
        assertEquals(3, r.path("links").size());
        assertEquals("PAY-2", r.path("links").get(2).path("name").asText());
        assertEquals("https://jira.example.com/browse/PAY-2", r.path("links").get(2).path("url").asText());
        assertEquals("issue", r.path("links").get(2).path("type").asText());

        ObjectNode untouched = (ObjectNode) mapper.readTree("{\"labels\":[{\"name\":\"feature\",\"value\":\"Login\"}]}");
        new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://j/{value}", "issue")
                .modify(untouched, new ModifierContext(Bucket.PASSED, null));
        assertTrue(untouched.path("links").isMissingNode());
    }

    @Test
    void removeLabelByNameAndValueOnlyRemovesThatOne() throws IOException {
        ObjectNode r = (ObjectNode) mapper.readTree("{\"labels\":["
                + "{\"name\":\"tag\",\"value\":\"smoke\"},{\"name\":\"tag\",\"value\":\"bug:PAY-9\"}]}");
        assertEquals(1, AllureJson.removeLabel(r, "tag", "bug:PAY-9"));
        assertEquals(List.of("smoke"), AllureJson.labels(r, "tag"));
    }

    @Test
    void dedupeLinksKeepsFirstOfSameNameAndUrl() throws IOException {
        ObjectNode r = (ObjectNode) mapper.readTree("{\"links\":["
                + "{\"name\":\"PAY-1\",\"url\":\"https://j/PAY-1\",\"type\":\"issue\"},"
                + "{\"name\":\"PAY-1\",\"url\":\"https://j/PAY-1\",\"type\":\"link\"},"
                + "{\"name\":\"PAY-1\",\"url\":\"https://other/PAY-1\",\"type\":\"issue\"},"
                + "{\"name\":\"Runbook\",\"url\":\"https://j/PAY-1\",\"type\":\"link\"},"
                + "{\"name\":\"PAY-1\",\"url\":\"https://j/PAY-1\",\"type\":\"issue\"}]}");

        new dev.allureprocessor.modifiers.DedupeLinksModifier().modify(r, new ModifierContext(Bucket.PASSED, null));

        assertEquals(3, r.path("links").size());
        assertEquals("issue", r.path("links").get(0).path("type").asText());
        assertEquals("https://other/PAY-1", r.path("links").get(1).path("url").asText());
        assertEquals("Runbook", r.path("links").get(2).path("name").asText());

        ObjectNode noLinks = (ObjectNode) mapper.readTree("{}");
        assertEquals(0, AllureJson.dedupeLinks(noLinks));
    }

    @Test
    void dedupeCatchesDuplicatesCreatedByEarlierModifiers() throws IOException {
        ObjectNode r = (ObjectNode) mapper.readTree("{\"labels\":[{\"name\":\"jira\",\"value\":\"PAY-7\"}],"
                + "\"links\":[{\"name\":\"PAY-7\",\"url\":\"https://j/PAY-7\",\"type\":\"issue\"}]}");
        ModifierContext ctx = new ModifierContext(Bucket.NOT_PASSED, null);
        new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://j/{value}", "issue").modify(r, ctx);
        assertEquals(2, r.path("links").size());
        new dev.allureprocessor.modifiers.DedupeLinksModifier().modify(r, ctx);
        assertEquals(1, r.path("links").size());
    }

    @Test
    void passesReportOptionsToAllureOnlyWhenSet() {
        AllureProcessor.Builder options = Main.parse(new String[] {
                "--results", "in", "--out", "out", "--name", "Nightly ({bucket})", "--lang", "de",
                "--config", "conf/allure.yml", "--configDirectory", "", "--profile", "  "});
        AllureRunner runner = new AllureRunner("allure", true, options.allureOptions());

        List<String> args = runner.generateArgs(Path.of("res"), Path.of("rep"), options.reportNameFor("not-passed"));
        assertEquals(List.of("--single-file", "--name", "Nightly (not-passed)", "--lang", "de",
                "--config", "conf/allure.yml"), args.subList(5, args.size()));

        AllureProcessor.Builder none = Main.parse(new String[] {"--results", "in", "--out", "out"});
        List<String> plain = new AllureRunner("allure", false, none.allureOptions())
                .generateArgs(Path.of("res"), Path.of("rep"), none.reportNameFor("passed"));
        assertEquals(List.of("generate", Path.of("res").toAbsolutePath().toString(), "-o",
                Path.of("rep").toAbsolutePath().toString(), "--clean"), plain);
    }

    @Test
    void reportFileNameDefaultsToIndexAndCanBeOverridden() throws IOException {
        AllureProcessor.Builder defaults = Main.parse(new String[] {"--results", "in", "--out", "out"});
        assertEquals("index.html", defaults.reportFileFor("not-passed"));

        AllureProcessor.Builder named = Main.parse(new String[] {
                "--results", "in", "--out", "out", "--report-file", "nightly-{bucket}.html"});
        assertEquals("nightly-passed.html", named.reportFileFor("passed"));

        AllureProcessor.Builder sameName = Main.parse(new String[] {
                "--results", "in", "--out", "out", "--report-file", "nightly.html"});
        assertEquals("nightly.html", sameName.reportFileFor("passed"));
        assertEquals("nightly.html", sameName.reportFileFor("not-passed"));

        Path generated = Files.createDirectories(tmp.resolve("generated"));
        Files.writeString(generated.resolve("index.html"), "<html>new</html>");
        Path target = Files.writeString(tmp.resolve("nightly-passed.html"), "<html>old</html>");
        AllureRunner.moveReport(generated, target);
        assertEquals("<html>new</html>", Files.readString(target));
        assertFalse(Files.exists(generated.resolve("index.html")));
    }

    @Test
    void builtInModifiersRunAfterAddedOnesAndCanBeTurnedOff() throws Exception {
        // adds a link that duplicates one already on r1; only the built-in dedupe removes it again
        ResultModifier duplicateLink = (result, ctx) -> {
            AllureJson.addLink(result, "Runbook", "https://wiki/runbook", "link");
            AllureJson.addLink(result, "Runbook", "https://wiki/runbook", "link");
        };

        AllureProcessor.builder().results(results).out(tmp.resolve("with")).skipGenerate(true)
                .addModifier(duplicateLink).run();
        assertEquals(1, mapper.readTree(tmp.resolve("with/results/passed/r1-result.json").toFile()).path("links").size());

        AllureProcessor.builder().results(results).out(tmp.resolve("without")).skipGenerate(true)
                .addModifier(duplicateLink).withoutDefaultModifiers().run();
        assertEquals(2, mapper.readTree(tmp.resolve("without/results/passed/r1-result.json").toFile()).path("links").size());
    }

    @Test
    void modifiersRunByPhaseThenInTheOrderAdded() throws Exception {
        List<String> order = new java.util.ArrayList<>();
        ResultModifier late = recording(order, "late", ResultModifier.Phase.LATE);
        ResultModifier early = recording(order, "early", ResultModifier.Phase.EARLY);
        ResultModifier first = (result, ctx) -> order.add("first");
        ResultModifier second = (result, ctx) -> order.add("second");

        // only r1 lands in the passed bucket of this folder, so each modifier is recorded once there
        Path single = Files.createDirectories(tmp.resolve("single"));
        Files.copy(results.resolve("r1-result.json"), single.resolve("r1-result.json"));
        AllureProcessor.builder().results(single).out(tmp.resolve("phases")).skipGenerate(true)
                .addModifier(late).addModifier(first).addModifier(early).addModifier(second).run();

        assertEquals(List.of("early", "first", "second", "late"), order);

        // dedupe is LATE, so it still runs after a modifier that was added behind it
        ObjectNode r = (ObjectNode) mapper.readTree("{\"labels\":[{\"name\":\"jira\",\"value\":\"PAY-7\"}],"
                + "\"links\":[{\"name\":\"PAY-7\",\"url\":\"https://j/PAY-7\",\"type\":\"issue\"}]}");
        Path dupes = Files.createDirectories(tmp.resolve("dupes"));
        r.put("uuid", "d1").put("status", "passed");
        mapper.writeValue(dupes.resolve("d1-result.json").toFile(), r);
        AllureProcessor.builder().results(dupes).out(tmp.resolve("dupes-out")).skipGenerate(true)
                .withoutDefaultModifiers()
                .addModifier(new dev.allureprocessor.modifiers.DedupeLinksModifier())
                .addModifier(new dev.allureprocessor.modifiers.LabelToLinkModifier("jira", "https://j/{value}", "issue"))
                .run();
        assertEquals(1, mapper.readTree(tmp.resolve("dupes-out/results/passed/d1-result.json").toFile())
                .path("links").size());
    }

    private static ResultModifier recording(List<String> order, String name, ResultModifier.Phase phase) {
        return new ResultModifier() {
            @Override
            public void modify(ObjectNode result, ModifierContext context) {
                order.add(name);
            }

            @Override
            public Phase phase() {
                return phase;
            }
        };
    }

    @Test
    void rejectsMissingOrContradictorySettings() {
        assertThrows(IllegalArgumentException.class, () -> AllureProcessor.builder().results(results).run());
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {
                "--results", "in", "--out", "out", "--no-single-file", "--report-file", "x.html"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {
                "--results", "in", "--out", "out", "--report-file", "sub/x.html"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--results", "in", "--out"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--bogus"}));
    }

    @Test
    void makesNonExecutableAllureBinaryExecutable() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path allure = Files.writeString(bin.resolve("allure"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(allure, PosixFilePermissions.fromString("rw-r-----"));

        assertEquals(allure, AllureRunner.locate("allure", tmp.resolve("missing") + File.pathSeparator + bin));
        assertEquals(allure, AllureRunner.locate(allure.toString(), null));
        assertEquals(null, AllureRunner.locate("allure", tmp.resolve("missing").toString()));

        AllureRunner.ensureExecutable(allure);
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(allure)));
    }

    private void result(String uuid, String historyId, String status, long stop, String extra) throws IOException {
        write(uuid + "-result.json", "{\"uuid\":\"" + uuid + "\",\"historyId\":\"" + historyId + "\",\"name\":\"" + uuid
                + "\",\"status\":\"" + status + "\",\"start\":" + (stop - 10) + ",\"stop\":" + stop
                + ",\"labels\":[{\"name\":\"feature\",\"value\":\"Login\"}]" + extra + "}");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(results.resolve(name), content);
    }

    private static List<String> children(JsonNode container) {
        return Stream.of(container.path("children")).flatMap(n -> {
            List<String> l = new java.util.ArrayList<>();
            n.forEach(c -> l.add(c.asText()));
            return l.stream();
        }).toList();
    }

    private static Set<String> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }
}
